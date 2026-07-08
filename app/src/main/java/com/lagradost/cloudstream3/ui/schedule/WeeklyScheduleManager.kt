package com.lagradost.cloudstream3.ui.schedule

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?
    )

    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co/"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300"
    private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/original"

    private val enrichMutex = Mutex()

    private const val CACHE_PREFS = "weekly_schedule_cache"
    private const val KEY_SCHEDULE_JSON = "schedule_json"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
    private const val SCHEDULE_CACHE_VERSION = 2 // Bump to clear old cached items
    private const val KEY_SCHEDULE_CACHE_VERSION = "schedule_cache_version"

    // --- Backdrop cache (permanent, AniList ID → TMDB backdrop URL) ---
    private const val BACKDROP_CACHE_PREFS = "anilist_tmdb_backdrop_cache"
    private const val BACKDROP_CACHE_VERSION = 4 // Bump to clear old w1280 cache
    private const val KEY_CACHE_VERSION = "cache_version"
    private const val NOT_FOUND_MARKER = "NOT_FOUND"

    // --- Logo cache (permanent, AniList ID → TMDB logo URL) ---
    private const val LOGO_CACHE_PREFS = "anilist_tmdb_logo_cache"

    private fun getBackdropPrefs(): SharedPreferences? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(BACKDROP_CACHE_PREFS, Context.MODE_PRIVATE)
        // Clear cache if version bumped (old Find endpoint results are useless)
        val storedVersion = prefs.getInt(KEY_CACHE_VERSION, 1)
        if (storedVersion < BACKDROP_CACHE_VERSION) {
            android.util.Log.d("SCHEDULE_BACKDROP", "Backdrop cache version mismatch ($storedVersion < $BACKDROP_CACHE_VERSION), clearing")
            prefs.edit().clear().putInt(KEY_CACHE_VERSION, BACKDROP_CACHE_VERSION).apply()
        }
        return prefs
    }

    fun getCachedBackdropUrl(anilistId: Int): String? {
        val url = getBackdropPrefs()?.getString(anilistId.toString(), null)
        if (url == NOT_FOUND_MARKER) {
            android.util.Log.d("SCHEDULE_BACKDROP", "Backdrop cache: ID=$anilistId → NOT_FOUND")
            return null
        }
        if (url != null) {
            android.util.Log.d("SCHEDULE_BACKDROP", "Backdrop cache hit: ID=$anilistId → $url")
        }
        return url
    }

    fun cacheBackdropUrl(anilistId: Int, url: String?) {
        getBackdropPrefs()?.edit()
            ?.putString(anilistId.toString(), url ?: NOT_FOUND_MARKER)
            ?.apply()
    }

    fun getCachedLogoUrl(anilistId: Int): String? {
        val ctx = context ?: return null
        val url = ctx.getSharedPreferences(LOGO_CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(anilistId.toString(), null)
        if (url == NOT_FOUND_MARKER) return null
        return url
    }

    fun cacheLogoUrl(anilistId: Int, url: String?) {
        val ctx = context ?: return
        ctx.getSharedPreferences(LOGO_CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(anilistId.toString(), url ?: NOT_FOUND_MARKER)
            .apply()
    }

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
                    put("banner", item.scheduleBannerUrl ?: JSONObject.NULL)
                    put("logo", item.scheduleLogoUrl ?: JSONObject.NULL)
                    put("episode", item.episodeNumber ?: JSONObject.NULL)
                    put("airingAt", item.airingAt)
                    put("type", item.scheduleType.name)
                    put("tmdbId", item.tmdbId ?: JSONObject.NULL)
                }
                jsonArray.put(obj)
            }
            getPrefs()?.edit()
                ?.putString(KEY_SCHEDULE_JSON, jsonArray.toString())
                ?.putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                ?.putInt(KEY_SCHEDULE_CACHE_VERSION, SCHEDULE_CACHE_VERSION)
                ?.apply()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun loadFromCache(): List<WeeklyScheduleItem> {
        return try {
            val prefs = getPrefs() ?: return emptyList()
            // Clear old cache if version bumped
            val storedVersion = prefs.getInt(KEY_SCHEDULE_CACHE_VERSION, 1)
            if (storedVersion < SCHEDULE_CACHE_VERSION) {
                android.util.Log.d("SCHEDULE_CACHE", "Schedule cache version mismatch ($storedVersion < $SCHEDULE_CACHE_VERSION), clearing")
                prefs.edit().clear().putInt(KEY_SCHEDULE_CACHE_VERSION, SCHEDULE_CACHE_VERSION).apply()
                return emptyList()
            }
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
                        scheduleBannerUrl = getCachedBackdropUrl(obj.getInt("id"))
                            ?: obj.optString("banner", null),
                        scheduleLogoUrl = getCachedLogoUrl(obj.getInt("id")),
                        episodeNumber = if (obj.isNull("episode")) null else obj.getInt("episode"),
                        airingAt = obj.getLong("airingAt"),
                        scheduleType = type,
                        tmdbId = if (obj.isNull("tmdbId")) null else obj.optInt("tmdbId", 0).takeIf { it > 0 }
                    )
                )
            }
            items.sortedBy { it.airingAt }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    fun getCachedOrEmpty(): List<WeeklyScheduleItem> = loadFromCache()

    fun isCacheValid(): Boolean {
        val prefs = getPrefs() ?: return false
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        return System.currentTimeMillis() - timestamp <= CACHE_TTL_MS
    }

    suspend fun fetchFreshSchedule(): List<WeeklyScheduleItem> {
        return enrichMutex.withLock {
            android.util.Log.d("SCHEDULE_BACKDROP", "fetchFreshSchedule called, fetching items...")
            val fresh = fetchAllSchedule()
            android.util.Log.d("SCHEDULE_BACKDROP", "Fetched ${fresh.size} items, saving to cache and starting enrichment...")
            saveToCache(fresh)
            enrichWithTmdbBackdrops(fresh)
            // Reload from cache so items have enriched backdrop URLs
            android.util.Log.d("SCHEDULE_BACKDROP", "Reloading items from cache with enriched backdrops...")
            loadFromCache()
        }
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
                                idMal
                                title {
                                    english
                                    romaji
                                    native
                                }
                                bannerImage
                                externalLinks {
                                    site
                                    id
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

                    val englishRaw = titleObj?.optString("english")?.trim() ?: ""
                    val hasEnglish = englishRaw.isNotBlank() && !englishRaw.equals("null", ignoreCase = true)

                    if (!hasEnglish) {
                        val nativeTitle = titleObj?.optString("native")?.trim() ?: ""
                        val hasJapanese = nativeTitle.any { ch ->
                            ch in '\u3040'..'\u309F' ||
                            ch in '\u30A0'..'\u30FF'
                        }
                        if (!hasJapanese) continue
                    }

                    val title = titleObj?.optString("english")?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: titleObj?.optString("romaji")?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
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

                    val anilistBanner = media.optString("bannerImage")?.trim()?.takeIf {
                        it.isNotBlank() && !it.equals("null", ignoreCase = true)
                    }

                    // Extract TMDB ID from AniList externalLinks (site == "tmdb")
                    val tmdbId = run {
                        val links = media.optJSONArray("externalLinks")
                        var found: Int? = null
                        if (links != null) {
                            for (l in 0 until links.length()) {
                                val link = links.getJSONObject(l)
                                if (link.optString("site", "").equals("tmdb", ignoreCase = true)) {
                                    val id = link.optInt("id", 0)
                                    if (id > 0) { found = id; break }
                                }
                            }
                        }
                        found
                    }

                    // Use cached TMDB backdrop if available, else AniList banner
                    val cachedBackdrop = getCachedBackdropUrl(mediaId)
                    val banner = cachedBackdrop ?: anilistBanner

                    items.add(
                        WeeklyScheduleItem(
                            scheduleId = mediaId,
                            scheduleName = title,
                            schedulePosterUrl = poster,
                            scheduleBannerUrl = banner,
                            scheduleLogoUrl = getCachedLogoUrl(mediaId),
                            episodeNumber = episode,
                            airingAt = airingAt * 1000,
                            scheduleType = ScheduleType.ANIME,
                            tmdbId = tmdbId
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
     * Batch-fetch TMDB backdrops for anime items missing high-quality banners.
     * Runs in background, updates cache for next display cycle.
     */
    private suspend fun enrichWithTmdbBackdrops(items: List<WeeklyScheduleItem>) = withContext(Dispatchers.IO) {
        android.util.Log.d("SCHEDULE_BACKDROP", "=== enrichWithTmdbBackdrops STARTED ===")
        val ctx = context
        if (ctx == null) {
            android.util.Log.e("SCHEDULE_BACKDROP", "Context is null, aborting")
            return@withContext
        }
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val tmdbApiKey = prefs.getString("tmdb_api_key", null)
        if (tmdbApiKey.isNullOrBlank()) {
            android.util.Log.e("SCHEDULE_BACKDROP", "TMDB API key is null or blank, aborting")
            return@withContext
        }
        android.util.Log.d("SCHEDULE_BACKDROP", "TMDB API key present, processing ${items.size} items")

        val needsBackdrop = items.filter { item ->
            item.scheduleType == ScheduleType.ANIME &&
            (getCachedBackdropUrl(item.scheduleId) == null || getCachedLogoUrl(item.scheduleId) == null)
        }.distinctBy { it.scheduleId }

        android.util.Log.d("SCHEDULE_BACKDROP", "Items needing enrichment: ${needsBackdrop.size} (out of ${items.size} total)")

        if (needsBackdrop.isEmpty()) {
            android.util.Log.d("SCHEDULE_BACKDROP", "No items need enrichment, done")
            return@withContext
        }

        // We need the full title list from AniList to search TMDB by name.
        // The items only have the resolved title. We'll search by that.
        val batchSize = 40
        needsBackdrop.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            if (batchIndex > 0) {
                android.util.Log.d("SCHEDULE_BACKDROP", "Waiting 10.5s before batch ${batchIndex + 1}...")
                delay(10_500)
            }

            android.util.Log.d("SCHEDULE_BACKDROP", "Processing batch ${batchIndex + 1}/${(needsBackdrop.size + batchSize - 1) / batchSize} (${batch.size} items)")

            for (item in batch) {
                try {
                    val tmdbId = item.tmdbId
                    if (tmdbId != null && tmdbId > 0) {
                        // Direct TMDB lookup by ID — no text search, no season-suffix issues
                        try {
                            val url = "$TMDB_BASE_URL/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=images&include_image_language=en,ja,null"
                            android.util.Log.d("SCHEDULE_BACKDROP", "TMDB by ID $tmdbId for: ${item.scheduleName} (AniList ID=${item.scheduleId})")
                            val json = JSONObject(app.get(url, timeout = 5000).text)
                            val backdropPath = json.optString("backdrop_path", "")
                            val posterPath = json.optString("poster_path", "")
                            val name = json.optString("name", item.scheduleName)
                            val chosen = if (!backdropPath.isNullOrBlank() && backdropPath != "null") {
                                backdropPath
                            } else if (!posterPath.isNullOrBlank() && posterPath != "null") {
                                posterPath
                            } else null

                            if (chosen != null) {
                                val backdropUrl = "$TMDB_BACKDROP_BASE$chosen"
                                cacheBackdropUrl(item.scheduleId, backdropUrl)
                                android.util.Log.d("SCHEDULE_BACKDROP", "✓ Matched by ID: ${item.scheduleName} → TMDB: $name → $backdropUrl")
                            } else {
                                cacheBackdropUrl(item.scheduleId, null)
                                android.util.Log.d("SCHEDULE_BACKDROP", "✗ No backdrop for: ${item.scheduleName} (TMDB ID=$tmdbId has no images)")
                            }

                            if (getCachedLogoUrl(item.scheduleId) == null) {
                                cacheLogosFromImagesJson(json.optJSONObject("images"), item)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SCHEDULE_BACKDROP", "✗ TMDB ID lookup failed for ${item.scheduleName}: ${e.message}")
                            cacheBackdropUrl(item.scheduleId, null)
                            cacheLogoUrl(item.scheduleId, null)
                        }
                    } else {
                        // Fallback: text search with season-suffix stripping + retry
                        enrichByTextSearch(item, tmdbApiKey)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SCHEDULE_BACKDROP", "✗ Failed for ${item.scheduleName}: ${e.message}")
                }
            }
        }

        android.util.Log.d("SCHEDULE_BACKDROP", "=== enrichWithTmdbBackdrops COMPLETE ===")
    }

    /**
     * Strip season / cour / part suffixes so TMDB text search can match the parent show.
     * e.g. "Grand Blue Dreaming Season 3" → "Grand Blue Dreaming"
     */
    private fun stripSeasonSuffix(title: String): String {
        return title
            .replace(Regex("\\s+(?:Season| Cour| Part| Cours)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+\\d+(?:st|nd|rd|th)\\s+Season.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:SP|OVA|ONA).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cacheLogosFromImagesJson(imagesJson: JSONObject?, item: WeeklyScheduleItem) {
        if (imagesJson == null) { cacheLogoUrl(item.scheduleId, null); return }
        val logos = imagesJson.optJSONArray("logos")
        if (logos == null || logos.length() == 0) { cacheLogoUrl(item.scheduleId, null); return }
        var bestLogo: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.getJSONObject(i)
            val lang = logo.optString("iso_639_1", "")
            if (lang == "en") { bestLogo = logo; break }
            if (lang == "ja" && bestLogo == null) bestLogo = logo
            if (bestLogo == null) bestLogo = logo
        }
        val logoPath = bestLogo?.optString("file_path", "")
        if (!logoPath.isNullOrBlank() && logoPath != "null") {
            val logoUrl = "https://image.tmdb.org/t/p/original$logoPath"
            cacheLogoUrl(item.scheduleId, logoUrl)
            android.util.Log.d("SCHEDULE_BACKDROP", "✓ Logo: ${item.scheduleName} → $logoUrl")
        } else {
            cacheLogoUrl(item.scheduleId, null)
        }
    }

    /**
     * Fallback TMDB lookup via text search when no AniList→TMDB ID is available.
     * Tries the full title, then a season-suffix-stripped title.
     */
    private suspend fun enrichByTextSearch(item: WeeklyScheduleItem, tmdbApiKey: String) {
        val queries = mutableListOf(item.scheduleName)
        val stripped = stripSeasonSuffix(item.scheduleName)
        if (stripped != item.scheduleName && stripped.isNotBlank()) {
            queries.add(stripped)
        }

        for ((idx, query) in queries.withIndex()) {
            val encodedTitle = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$TMDB_BASE_URL/search/tv?api_key=$tmdbApiKey&query=$encodedTitle&language=en-US&page=1&append_to_response=images&include_image_language=en,ja,null"
            android.util.Log.d("SCHEDULE_BACKDROP", "Searching TMDB for: $query (AniList ID=${item.scheduleId})${if (idx > 0) " [season-stripped]" else ""}")
            val json = JSONObject(app.get(url, timeout = 5000).text)
            val results = json.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val firstResult = results.getJSONObject(0)
                val backdropPath = firstResult.optString("backdrop_path", "")
                val posterPath = firstResult.optString("poster_path", "")
                val tmdbName = firstResult.optString("name", "")
                val chosen = if (!backdropPath.isNullOrBlank() && backdropPath != "null") {
                    backdropPath
                } else if (!posterPath.isNullOrBlank() && posterPath != "null") {
                    posterPath
                } else null

                if (chosen != null) {
                    val backdropUrl = "$TMDB_BACKDROP_BASE$chosen"
                    cacheBackdropUrl(item.scheduleId, backdropUrl)
                    android.util.Log.d("SCHEDULE_BACKDROP", "✓ Matched: ${item.scheduleName} → TMDB: $tmdbName → $backdropUrl")
                } else {
                    cacheBackdropUrl(item.scheduleId, null)
                    android.util.Log.d("SCHEDULE_BACKDROP", "✗ No backdrop for: ${item.scheduleName} (TMDB matched: $tmdbName but no images)")
                }

                if (getCachedLogoUrl(item.scheduleId) == null) {
                    cacheLogosFromImagesJson(firstResult.optJSONObject("images"), item)
                }
                return
            }
        }
        cacheBackdropUrl(item.scheduleId, null)
        cacheLogoUrl(item.scheduleId, null)
        android.util.Log.d("SCHEDULE_BACKDROP", "✗ No TMDB results for: ${item.scheduleName}")
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
                    val bannerUrl = details.backdropPath?.let { "$TMDB_BACKDROP_BASE$it" }

                    allItems.add(
                        WeeklyScheduleItem(
                            scheduleId = id,
                            scheduleName = name,
                            schedulePosterUrl = posterUrl,
                            scheduleBannerUrl = bannerUrl,
                            scheduleLogoUrl = null,
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

            val backdropMatch = Regex(""""backdrop_path"\s*:\s*"([^"]+)"""").find(text)
            val backdropPath = backdropMatch?.groupValues?.get(1)

            val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(text)
            val name = nameMatch?.groupValues?.get(1)

            if (nextAirDate != null) {
                TmdbOnAirResult(
                    id = showId,
                    name = name,
                    title = name,
                    posterPath = posterPath,
                    backdropPath = backdropPath,
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
