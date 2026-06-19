package com.lagradost.cloudstream3.ui.schedule

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object WeeklyScheduleManager {

    // --- AniList response models ---
    data class AniListScheduleRoot(val data: AniListScheduleData?)
    data class AniListScheduleData(val Page: AniListSchedulePage?)
    data class AniListSchedulePage(val airingSchedules: List<AniListAiringEntry>?)
    data class AniListAiringEntry(
        val id: Int?,
        val episode: Int?,
        val airingAt: Long?,
        val timeUntilAiring: Long?,
        val media: AniListScheduleMedia?
    )
    data class AniListScheduleMedia(
        val id: Int?,
        val title: AniListScheduleTitle?,
        val coverImage: AniListScheduleCover?
    )
    data class AniListScheduleTitle(val romaji: String?, val english: String?)
    data class AniListScheduleCover(val large: String?, val medium: String?)

    // --- TMDB response models ---
    data class TmdbOnAirResponse(
        val page: Int?,
        val results: List<TmdbOnAirResult>?,
        val total_pages: Int?,
        val total_results: Int?
    )
    data class TmdbOnAirResult(
        val id: Int?,
        val name: String?,
        val title: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?
    )

    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co/"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300"

    private const val CACHE_PREFS = "weekly_schedule_cache"
    private const val KEY_SCHEDULE_JSON = "schedule_json"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private fun getPrefs(): SharedPreferences? {
        val ctx = context ?: return null
        return ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    }

    private fun saveToCache(items: List<WeeklyScheduleItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                if (item.scheduleName.isBlank() || item.scheduleName == "null") {
                    android.util.Log.w("SCHEDULE_CACHE", "Saving item with suspicious name: id=${item.scheduleId} name=[${item.scheduleName}]")
                }
                val obj = JSONObject().apply {
                    put("id", item.scheduleId)
                    put("name", item.scheduleName)
                    put("poster", item.schedulePosterUrl ?: JSONObject.NULL)
                    put("episode", item.episodeNumber ?: JSONObject.NULL)
                    put("airingAt", item.airingAt)
                    put("type", item.scheduleType.name)
                }
                jsonArray.put(obj)
            }
            getPrefs()?.edit()
                ?.putString(KEY_SCHEDULE_JSON, jsonArray.toString())
                ?.putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                ?.apply()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun loadFromCache(): List<WeeklyScheduleItem> {
        return try {
            val prefs = getPrefs() ?: return emptyList()
            val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return emptyList()

            val json = prefs.getString(KEY_SCHEDULE_JSON, null) ?: return emptyList()
            val jsonArray = JSONArray(json)
            val items = mutableListOf<WeeklyScheduleItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val type = try {
                    ScheduleType.valueOf(obj.getString("type"))
                } catch (e: Exception) {
                    continue
                }
                val name = obj.optString("name", "")
                if (name.isBlank()) {
                    android.util.Log.w("SCHEDULE_CACHE", "Skipping cached item with blank name: id=${obj.optInt("id", 0)}, rawName=[${obj.optString("name", "__NULL__")}]")
                    continue
                }

                items.add(
                    WeeklyScheduleItem(
                        scheduleId = obj.getInt("id"),
                        scheduleName = name,
                        schedulePosterUrl = obj.optString("poster", null),
                        episodeNumber = if (obj.isNull("episode")) null else obj.getInt("episode"),
                        airingAt = obj.getLong("airingAt"),
                        scheduleType = type
                    )
                )
            }
            items.sortedBy { it.airingAt }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    /**
     * Returns cached data immediately if valid.
     */
    fun getCachedOrEmpty(): List<WeeklyScheduleItem> = loadFromCache()

    /**
     * Fetch fresh schedule from APIs and cache it.
     */
    suspend fun fetchFreshSchedule(): List<WeeklyScheduleItem> {
        val fresh = fetchAllSchedule()
        saveToCache(fresh)
        return fresh
    }

    private suspend fun fetchAllSchedule(): List<WeeklyScheduleItem> {
        val items = mutableListOf<WeeklyScheduleItem>()

        val animeItems = fetchAnimeSchedule()
        items.addAll(animeItems)

        val ctx = context ?: return items
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val tmdbApiKey = prefs.getString("tmdb_api_key", null)
        if (!tmdbApiKey.isNullOrBlank()) {
            val tvItems = fetchTvSchedule(tmdbApiKey)
            items.addAll(tvItems)
        }

        return items.sortedBy { it.airingAt }
    }

    /**
     * Fetch this week's anime schedule from AniList.
     */
    suspend fun fetchAnimeSchedule(): List<WeeklyScheduleItem> {
        return try {
            val now = ZonedDateTime.now()
            val weekStart = now.with(DayOfWeek.MONDAY).withHour(0).withMinute(0).withSecond(0)
            val weekEnd = weekStart.plusDays(7)
            val startEpoch = weekStart.toInstant().toEpochMilli() / 1000
            val endEpoch = weekEnd.toInstant().toEpochMilli() / 1000

            val query = """
                query (${"$"}startAt: Int, ${"$"}endAt: Int, ${"$"}page: Int) {
                    Page(page: ${"$"}page, perPage: 50) {
                        pageInfo {
                            hasNextPage
                        }
                        airingSchedules(
                            airingAt_greater: ${"$"}startAt,
                            airingAt_lesser: ${"$"}endAt,
                            sort: TIME
                        ) {
                            id
                            episode
                            airingAt
                            media {
                                id
                                title {
                                    english
                                    romaji
                                    native
                                }
                                coverImage {
                                    extraLarge
                                    large
                                    medium
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            val items = mutableListOf<WeeklyScheduleItem>()
            var page = 1

            do {
                val data = mapOf(
                    "query" to query,
                    "variables" to mapOf(
                        "startAt" to startEpoch,
                        "endAt" to endEpoch,
                        "page" to page
                    ).toJson()
                )

                val res = app.post(
                    ANILIST_GRAPHQL_URL,
                    data = data,
                    timeout = 8000
                ).text

                val jsonObj = org.json.JSONObject(res)
                val pageInfo = jsonObj.getJSONObject("data").getJSONObject("Page").getJSONObject("pageInfo")
                val hasNextPage = pageInfo.optBoolean("hasNextPage", false)
                val schedules = jsonObj.getJSONObject("data").getJSONObject("Page").getJSONArray("airingSchedules")

                for (i in 0 until schedules.length()) {
                    val entry = schedules.getJSONObject(i)
                    val episode = entry.optInt("episode", 0)
                    val airingAt = entry.optLong("airingAt", 0)
                    if (airingAt <= 0) continue

                    val media = entry.optJSONObject("media") ?: continue
                    val mediaId = media.optInt("id", 0)
                    val titleObj = media.optJSONObject("title")

                    val rawEnglish = titleObj?.optString("english", "__MISSING__") ?: "__NULL_OBJ__"
                    val rawRomaji = titleObj?.optString("romaji", "__MISSING__") ?: "__NULL_OBJ__"
                    val rawNative = titleObj?.optString("native", "__MISSING__") ?: "__NULL_OBJ__"

                    val title = titleObj?.optString("english")?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: titleObj?.optString("romaji")?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: titleObj?.optString("native")?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: continue

                    android.util.Log.d(
                        "SCHEDULE_TITLE",
                        "ID=$mediaId | english=[$rawEnglish] romaji=[$rawRomaji] native=[$rawNative] → resolved=[$title]"
                    )

                    if (episode > 100) continue

                    val coverObj = media.optJSONObject("coverImage")
                    val poster = coverObj?.optString("extraLarge")
                        ?: coverObj?.optString("large")
                        ?: coverObj?.optString("medium")

                    items.add(
                        WeeklyScheduleItem(
                            scheduleId = mediaId,
                            scheduleName = title,
                            schedulePosterUrl = poster,
                            episodeNumber = episode,
                            airingAt = airingAt * 1000,
                            scheduleType = ScheduleType.ANIME
                        )
                    )
                }

                page++
            } while (hasNextPage)

            items.sortedBy { it.airingAt }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    /**
     * Fetch this week's TV show schedule from TMDB.
     */
    suspend fun fetchTvSchedule(apiKey: String): List<WeeklyScheduleItem> {
        return try {
            val allItems = mutableListOf<WeeklyScheduleItem>()
            val today = LocalDate.now()
            val weekEnd = today.plusDays(7)

            for (page in 1..3) {
                val url = "$TMDB_BASE_URL/tv/on_the_air?api_key=$apiKey&page=$page&language=en-US"
                val response = app.get(url, timeout = 8000)
                val parsed = tryParseJson<TmdbOnAirResponse>(response.text) ?: break

                val results = parsed.results ?: break
                if (results.isEmpty()) break

                for (result in results) {
                    val id = result.id ?: continue
                    val name = result.name ?: result.title ?: continue

                    val details = fetchTvShowDetails(apiKey, id) ?: continue
                    val nextAir = details.firstAirDate ?: continue

                    val airDate = try {
                        LocalDate.parse(nextAir)
                    } catch (e: Exception) {
                        continue
                    }

                    if (airDate.isBefore(today) || airDate.isAfter(weekEnd)) continue

                    val posterUrl = details.posterPath?.let { "$TMDB_IMAGE_BASE$it" }

                    allItems.add(
                        WeeklyScheduleItem(
                            scheduleId = id,
                            scheduleName = name,
                            schedulePosterUrl = posterUrl,
                            episodeNumber = null,
                            airingAt = airDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            scheduleType = ScheduleType.TV
                        )
                    )
                }
            }

            allItems.sortedBy { it.airingAt }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private suspend fun fetchTvShowDetails(apiKey: String, showId: Int): TmdbOnAirResult? {
        return try {
            val url = "$TMDB_BASE_URL/tv/$showId?api_key=$apiKey&language=en-US"
            val response = app.get(url, timeout = 5000)
            val text = response.text

            val nextAirMatch = Regex(""""air_date"\s*:\s*"([^"]+)"""").find(text)
            val nextAirDate = nextAirMatch?.groupValues?.get(1)

            val posterMatch = Regex(""""poster_path"\s*:\s*"([^"]+)"""").find(text)
            val posterPath = posterMatch?.groupValues?.get(1)

            val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(text)
            val name = nameMatch?.groupValues?.get(1)

            if (nextAirDate != null) {
                TmdbOnAirResult(
                    id = showId,
                    name = name,
                    title = name,
                    posterPath = posterPath,
                    firstAirDate = nextAirDate,
                    voteAverage = null
                )
            } else null
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    fun groupByDay(items: List<WeeklyScheduleItem>): List<DaySchedule> {
        val today = LocalDate.now()
        val weekStart = today.with(DayOfWeek.MONDAY)

        return (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            val dayOfWeek = date.dayOfWeek
            val dayItems = items.filter {
                val itemDate = Instant.ofEpochMilli(it.airingAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                itemDate == date
            }
            DaySchedule(dayOfWeek, dayItems)
        }
    }

    fun getPreviewItems(items: List<WeeklyScheduleItem>): List<WeeklyScheduleItem> {
        return items.take(6)
    }
}
