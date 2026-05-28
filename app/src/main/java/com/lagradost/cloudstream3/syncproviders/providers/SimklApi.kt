package com.lagradost.cloudstream3.syncproviders.providers

import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse.Companion.readIdFromString
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthPinData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.DataStoreHelper.toYear
import com.lagradost.cloudstream3.utils.txt
import java.math.BigInteger
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Rate limiter for Simkl API - enforces 1 request per second per CLIENT_ID.
 * [SIMKL_DEFINITIVE_FIX][PHASE4]
 */
private class RateLimiter(
    private val permitsPerSecond: Int = 1,
    private val minIntervalMs: Long = 1000L
) {
    private val lastRequestTime = AtomicLong(0)

    suspend fun acquire() {
        val now = System.currentTimeMillis()
        val lastRequest = lastRequestTime.get()
        val timeSinceLastRequest = now - lastRequest

        if (timeSinceLastRequest < minIntervalMs) {
            val waitTime = minIntervalMs - timeSinceLastRequest
            android.util.Log.d("[SIMKL_RATE_LIMIT]", "Rate limiting: waiting ${waitTime}ms")
            kotlinx.coroutines.delay(waitTime)
        }

        lastRequestTime.set(System.currentTimeMillis())
    }

    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        val lastRequest = lastRequestTime.get()
        val timeSinceLastRequest = now - lastRequest

        return if (timeSinceLastRequest >= minIntervalMs) {
            lastRequestTime.set(now)
            true
        } else {
            false
        }
    }
}

/**
 * Error response handler with exponential backoff for Simkl API.
 * Handles 429 (rate limit), 401 (auth), 500 (server) errors.
 * [SIMKL_DEFINITIVE_FIX][PHASE5]
 */
private object ErrorHandler {
    private const val MAX_RETRIES = 3
    private val retryDelays = listOf(2000L, 4000L, 8000L) // 2s, 4s, 8s

    data class ErrorResult<T>(
        val data: T?,
        val errorCode: Int?,
        val isSuccess: Boolean
    )

    suspend fun <T> executeWithRetry(
        rateLimiter: RateLimiter,
        block: suspend () -> T?
    ): ErrorResult<T> {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                // Apply rate limiting before each attempt
                rateLimiter.acquire()

                val result = block()
                return ErrorResult(result, null, true)
            } catch (e: Exception) {
                lastException = e
                val errorCode = extractErrorCode(e)

                android.util.Log.e("[SIMKL_ERROR]", "API call failed (attempt ${attempt + 1}/$MAX_RETRIES): code=$errorCode", e)

                when (errorCode) {
                    429 -> {
                        // Rate limit - use exponential backoff (Retry-After parsing removed for simplicity)
                        val delay = retryDelays.getOrElse(attempt) { 5000L }
                        android.util.Log.w("[SIMKL_ERROR]", "Rate limited (429), waiting ${delay}ms")
                        kotlinx.coroutines.delay(delay)
                    }
                    401 -> {
                        // Auth failure - don't retry, trigger reauth
                        android.util.Log.e("[SIMKL_ERROR]", "Authentication failed (401), triggering reauth")
                        return ErrorResult(null, 401, false)
                    }
                    in 500..599 -> {
                        // Server error - exponential backoff
                        val delay = retryDelays.getOrElse(attempt) { 8000L }
                        android.util.Log.w("[SIMKL_ERROR]", "Server error ($errorCode), retrying in ${delay}ms")
                        kotlinx.coroutines.delay(delay)
                    }
                    else -> {
                        // Other errors - don't retry
                        return ErrorResult(null, errorCode, false)
                    }
                }
            }
        }

        // All retries exhausted
        android.util.Log.e("[SIMKL_ERROR]", "All $MAX_RETRIES retries exhausted")
        return ErrorResult(null, extractErrorCode(lastException), false)
    }

    private fun extractErrorCode(e: Exception?): Int? {
        // Extract HTTP status code from exception message if available
        val message = e?.message ?: return null
        // Try to find status code patterns like "HTTP 429" or "Code: 429"
        val codePattern = Regex("(HTTP|Code|code)[:\\s]*(\\d{3})").find(message)
        return codePattern?.groupValues?.get(2)?.toIntOrNull()
    }
}

class SimklApi : SyncAPI() {
    override var name = "Simkl"
    override val idPrefix = "simkl"

    val key = "simkl-key"
    override val redirectUrlIdentifier = "simkl"
    override val hasOAuth2 = true
    override val hasPin = true
    override var requireLibraryRefresh = true
    override var mainUrl = "https://api.simkl.com"
    override val icon = R.drawable.simkl_logo
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Simkl

    /** Automatically adds simkl auth headers */
    // private val interceptor = HeaderInterceptor()

    /**
     * This is required to override the reported last activity as simkl activites
     * may not always update based on testing.
     */
    private var lastScoreTime = -1L

    /**
     * Global rate limiter for Simkl API - 1 request per second per CLIENT_ID.
     * [SIMKL_DEFINITIVE_FIX][PHASE4]
     */
    private val rateLimiter = RateLimiter(permitsPerSecond = 1, minIntervalMs = 1000L)

    private object SimklCache {
        private const val SIMKL_CACHE_KEY = "SIMKL_API_CACHE"

        enum class CacheTimes(val value: String) {
            OneMonth("30d"),
            ThirtyMinutes("30m")
        }

        private class SimklCacheWrapper<T>(
            @JsonProperty("obj") val obj: T?,
            @JsonProperty("validUntil") val validUntil: Long,
            @JsonProperty("cacheTime") val cacheTime: Long = unixTime,
        ) {
            /** Returns true if cache is newer than cacheDays */
            fun isFresh(): Boolean {
                return validUntil > unixTime
            }

            fun remainingTime(): Duration {
                val unixTime = unixTime
                return if (validUntil > unixTime) {
                    (validUntil - unixTime).toDuration(DurationUnit.SECONDS)
                } else {
                    Duration.ZERO
                }
            }
        }

        fun cleanOldCache() {
            getKeys(SIMKL_CACHE_KEY)?.forEach {
                val isOld = CloudStreamApp.getKey<SimklCacheWrapper<Any>>(it)?.isFresh() == false
                if (isOld) {
                    removeKey(it)
                }
            }
        }

        fun <T> setKey(path: String, value: T, cacheTime: Duration) {
            debugPrint { "Set cache: $SIMKL_CACHE_KEY/$path for ${cacheTime.inWholeDays} days or ${cacheTime.inWholeSeconds} seconds." }
            setKey(
                SIMKL_CACHE_KEY,
                path,
                // Storing as plain sting is required to make generics work.
                SimklCacheWrapper(value, unixTime + cacheTime.inWholeSeconds).toJson()
            )
        }

        /**
         * Gets cached object, if object is not fresh returns null and removes it from cache
         */
        inline fun <reified T : Any> getKey(path: String): T? {
            val cache = getKey<String>(SIMKL_CACHE_KEY, path)?.let {
                tryParseJson<SimklCacheWrapper<T>>(it)
            }

            return if (cache?.isFresh() == true) {
                debugPrint {
                    "Cache hit at: $SIMKL_CACHE_KEY/$path. " +
                            "Remains fresh for ${cache.remainingTime().inWholeDays} days or ${cache.remainingTime().inWholeSeconds} seconds."
                }
                cache.obj
            } else {
                debugPrint { "Cache miss at: $SIMKL_CACHE_KEY/$path" }
                removeKey(SIMKL_CACHE_KEY, path)
                null
            }
        }
    }

    companion object {
        private const val CLIENT_ID: String = BuildConfig.SIMKL_CLIENT_ID
        private const val CLIENT_SECRET: String = BuildConfig.SIMKL_CLIENT_SECRET
        const val SIMKL_CACHED_LIST: String = "simkl_cached_list"
        const val SIMKL_CACHED_LIST_TIME: String = "simkl_cached_time"

        /** 2014-09-01T09:10:11Z -> 1409562611 */
        private const val SIMKL_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        fun getUnixTime(string: String?): Long? {
            return try {
                SimpleDateFormat(SIMKL_DATE_FORMAT, Locale.getDefault()).apply {
                    this.timeZone = TimeZone.getTimeZone("UTC")
                }.parse(
                    string ?: return null
                )?.toInstant()?.epochSecond
            } catch (e: Exception) {
                logError(e)
                return null
            }
        }

        /** 1409562611 -> 2014-09-01T09:10:11Z */
        fun getDateTime(unixTime: Long?): String? {
            return try {
                SimpleDateFormat(SIMKL_DATE_FORMAT, Locale.getDefault()).apply {
                    this.timeZone = TimeZone.getTimeZone("UTC")
                }.format(
                    Date.from(
                        Instant.ofEpochSecond(
                            unixTime ?: return null
                        )
                    )
                )
            } catch (e: Exception) {
                null
            }
        }

        fun getPosterUrl(poster: String): String {
            return "https://wsrv.nl/?url=https://simkl.in/posters/${poster}_m.webp"
        }

        /**
         * Score normalization utilities.
         * Maps various score formats to Simkl's 1-10 scale.
         * [SIMKL_DEFINITIVE_FIX][PHASE6]
         */
        object ScoreNormalization {
            /**
             * Normalize a score from any scale to Simkl's 1-10 scale.
             * Examples:
             * - AniList 80% -> Simkl 8
             * - MAL 8/10 -> Simkl 8
             * - 100-point scale: 75 -> Simkl 8
             * - 5-star scale: 4 -> Simkl 8
             */
            fun normalizeToSimkl(score: Int, fromMax: Int): Int {
                return when {
                    fromMax <= 0 -> score.coerceIn(1, 10)
                    fromMax == 10 -> score.coerceIn(1, 10) // Already 1-10 scale
                    fromMax == 100 -> (score / 10.0).roundToInt().coerceIn(1, 10) // Percentage
                    fromMax == 5 -> (score * 2).coerceIn(1, 10) // 5-star scale
                    else -> ((score * 10.0) / fromMax).roundToInt().coerceIn(1, 10) // Proportional
                }
            }

            /**
             * Normalize from decimal score (e.g., 7.5/10 -> 8)
             */
            fun normalizeFromDecimal(score: Double, fromMax: Double = 10.0): Int {
                return ((score * 10.0) / fromMax).roundToInt().coerceIn(1, 10)
            }
        }

        /**
         * Episode progress normalization utilities.
         * Simkl tracks integer episodes only (watched/not watched).
         * [SIMKL_DEFINITIVE_FIX][PHASE6]
         */
        object ProgressNormalization {
            /**
             * Convert fractional progress to integer episode count.
             * Episode is considered watched if >50% completed.
             * @param watchedEpisodes Number of fully watched episodes
             * @param progressPercent Progress in current episode (0-100)
             * @return Total episodes to report to Simkl
             */
            fun calculateEpisodeProgress(
                watchedEpisodes: Int,
                progressPercent: Float
            ): Int {
                return watchedEpisodes + if (progressPercent > 50) 1 else 0
            }

            /**
             * Convert decimal episode progress (e.g., 5.7 = 5 episodes + 70% of next)
             * to integer count for Simkl.
             */
            fun fromDecimalProgress(decimalProgress: Float): Int {
                val whole = decimalProgress.toInt()
                val fraction = decimalProgress - whole
                return whole + if (fraction > 0.5f) 1 else 0
            }
        }

        fun getUrlFromId(id: Int): String {
            return "https://simkl.com/shows/$id"
        }

        enum class SimklListStatusType(
            var value: Int,
            @StringRes val stringRes: Int,
            val originalName: String?
        ) {
            Watching(0, R.string.type_watching, "watching"),
            Completed(1, R.string.type_completed, "completed"),
            Paused(2, R.string.type_on_hold, "hold"),
            Dropped(3, R.string.type_dropped, "dropped"),
            Planning(4, R.string.type_plan_to_watch, "plantowatch"),
            ReWatching(5, R.string.type_re_watching, "watching"),
            None(-1, R.string.none, null);

            companion object {
                fun fromString(string: String): SimklListStatusType? {
                    return SimklListStatusType.entries.firstOrNull {
                        it.originalName == string
                    }
                }
            }
        }

        // -------------------
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        data class TokenRequest(
            @JsonProperty("code") val code: String,
            @JsonProperty("client_id") val clientId: String = CLIENT_ID,
            @JsonProperty("client_secret") val clientSecret: String = CLIENT_SECRET,
            @JsonProperty("redirect_uri") val redirectUri: String = "$APP_STRING://simkl",
            @JsonProperty("grant_type") val grantType: String = "authorization_code"
        )

        data class TokenResponse(
            /** No expiration date */
            @JsonProperty("access_token") val accessToken: String,
            @JsonProperty("token_type") val tokenType: String,
            @JsonProperty("scope") val scope: String
        )
        // -------------------

        /** https://simkl.docs.apiary.io/#reference/users/settings/receive-settings */
        data class SettingsResponse(
            @JsonProperty("user")
            val user: User,
            @JsonProperty("account")
            val account: Account,
        ) {
            data class User(
                @JsonProperty("name")
                val name: String,
                /** Url */
                @JsonProperty("avatar")
                val avatar: String
            )

            data class Account(
                @JsonProperty("id")
                val id: Int,
            )
        }

        data class PinAuthResponse(
            @JsonProperty("result") val result: String,
            @JsonProperty("device_code") val deviceCode: String,
            @JsonProperty("user_code") val userCode: String,
            @JsonProperty("verification_url") val verificationUrl: String,
            @JsonProperty("expires_in") val expiresIn: Int,
            @JsonProperty("interval") val interval: Int,
        )

        data class PinExchangeResponse(
            @JsonProperty("result") val result: String,
            @JsonProperty("message") val message: String? = null,
            @JsonProperty("access_token") val accessToken: String? = null,
        )

        // -------------------
        data class ActivitiesResponse(
            @JsonProperty("all") val all: String?,
            @JsonProperty("tv_shows") val tvShows: UpdatedAt,
            @JsonProperty("anime") val anime: UpdatedAt,
            @JsonProperty("movies") val movies: UpdatedAt,
        ) {
            data class UpdatedAt(
                @JsonProperty("all") val all: String?,
                @JsonProperty("removed_from_list") val removedFromList: String?,
                @JsonProperty("rated_at") val ratedAt: String?,
            )
        }

        /** https://simkl.docs.apiary.io/#reference/tv/episodes/get-tv-show-episodes */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        data class EpisodeMetadata(
            @JsonProperty("title") val title: String?,
            @JsonProperty("description") val description: String?,
            @JsonProperty("season") val season: Int?,
            @JsonProperty("episode") val episode: Int,
            @JsonProperty("img") val img: String?
        ) {
            companion object {
                fun convertToEpisodes(list: List<EpisodeMetadata>?): List<MediaObject.Season.Episode>? {
                    return list?.map {
                        MediaObject.Season.Episode(it.episode)
                    }
                }

                fun convertToSeasons(list: List<EpisodeMetadata>?): List<MediaObject.Season>? {
                    return list?.filter { it.season != null }?.groupBy {
                        it.season
                    }?.mapNotNull { (season, episodes) ->
                        convertToEpisodes(episodes)?.let { MediaObject.Season(season!!, it) }
                    }?.ifEmpty { null }
                }
            }
        }

        /**
         * https://simkl.docs.apiary.io/#introduction/about-simkl-api/standard-media-objects
         * Useful for finding shows from metadata
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        open class MediaObject(
            @JsonProperty("title") val title: String?,
            @JsonProperty("year") val year: Int?,
            @JsonProperty("ids") val ids: Ids?,
            @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
            @JsonProperty("status") val status: String? = null,
            @JsonProperty("poster") val poster: String? = null,
            @JsonProperty("type") val type: String? = null,
            @JsonProperty("seasons") val seasons: List<Season>? = null,
            @JsonProperty("episodes") val episodes: List<Season.Episode>? = null
        ) {
            fun hasEnded(): Boolean {
                return status == "released" || status == "ended"
            }

            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            data class Season(
                @JsonProperty("number") val number: Int,
                @JsonProperty("episodes") val episodes: List<Episode>
            ) {
                data class Episode(@JsonProperty("number") val number: Int)
            }

            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            data class Ids(
                @JsonProperty("simkl") val simkl: Int?,
                @JsonProperty("imdb") val imdb: String? = null,
                @JsonProperty("tmdb") val tmdb: String? = null,
                @JsonProperty("mal") val mal: String? = null,
                @JsonProperty("anilist") val anilist: String? = null,
            ) {
                companion object {
                    fun fromMap(map: Map<SimklSyncServices, String>): Ids {
                        return Ids(
                            simkl = map[SimklSyncServices.Simkl]?.toIntOrNull(),
                            imdb = map[SimklSyncServices.Imdb],
                            tmdb = map[SimklSyncServices.Tmdb],
                            mal = map[SimklSyncServices.Mal],
                            anilist = map[SimklSyncServices.AniList]
                        )
                    }
                }
            }

            fun toSyncSearchResult(): SyncAPI.SyncSearchResult? {
                return SyncAPI.SyncSearchResult(
                    this.title ?: return null,
                    "Simkl",
                    this.ids?.simkl?.toString() ?: return null,
                    getUrlFromId(this.ids.simkl),
                    this.poster?.let { getPosterUrl(it) },
                    if (this.type == "movie") TvType.Movie else TvType.TvSeries
                )
            }
        }

        class SimklScoreBuilder private constructor() {
            data class Builder(
                private var url: String? = null,
                private var headers: Map<String, String>? = null,
                private var ids: MediaObject.Ids? = null,
                private var score: Int? = null,
                private var status: Int? = null,
                private var addEpisodes: Pair<List<MediaObject.Season>?, List<MediaObject.Season.Episode>?>? = null,
                private var removeEpisodes: Pair<List<MediaObject.Season>?, List<MediaObject.Season.Episode>?>? = null,
                // Required for knowing if the status should be overwritten
                private var onList: Boolean = false
            ) {
                fun token(token: AuthToken) = apply { this.headers = getHeaders(token) }
                fun apiUrl(url: String) = apply { this.url = url }
                fun ids(ids: MediaObject.Ids) = apply { this.ids = ids }
                fun score(score: Int?, oldScore: Int?) = apply {
                    if (score != oldScore) {
                        this.score = score
                    }
                }

                fun status(newStatus: Int?, oldStatus: Int?) = apply {
                    onList = oldStatus != null
                    // Only set status if its new
                    if (newStatus != oldStatus) {
                        this.status = newStatus
                    } else {
                        this.status = null
                    }
                }

                fun episodes(
                    allEpisodes: List<EpisodeMetadata>?,
                    newEpisodes: Int?,
                    oldEpisodes: Int?,
                ) = apply {
                    if (allEpisodes == null || newEpisodes == null) return@apply

                    fun getEpisodes(rawEpisodes: List<EpisodeMetadata>) =
                        if (rawEpisodes.any { it.season != null }) {
                            EpisodeMetadata.convertToSeasons(rawEpisodes) to null
                        } else {
                            null to EpisodeMetadata.convertToEpisodes(rawEpisodes)
                        }

                    // Do not add episodes if there is no change
                    if (newEpisodes > (oldEpisodes ?: 0)) {
                        this.addEpisodes = getEpisodes(allEpisodes.take(newEpisodes))

                        // Set to watching if episodes are added and there is no current status
                        if (!onList) {
                            status = SimklListStatusType.Watching.value
                        }
                    }
                    if ((oldEpisodes ?: 0) > newEpisodes) {
                        this.removeEpisodes = getEpisodes(allEpisodes.drop(newEpisodes))
                    }
                }

                suspend fun execute(): Boolean {
                    val time = getDateTime(unixTime)
                    val headers = this.headers ?: emptyMap()
                    return if (this.status == SimklListStatusType.None.value) {
                        app.post(
                            "$url/sync/history/remove",
                            json = StatusRequest(
                                shows = listOf(HistoryMediaObject(ids = ids)),
                                movies = emptyList()
                            ),
                            headers = headers
                        ).isSuccessful
                    } else {
                        val statusResponse = this.status?.let { setStatus ->
                            val newStatus =
                                SimklListStatusType.entries
                                    .firstOrNull { it.value == setStatus }?.originalName
                                    ?: SimklListStatusType.Watching.originalName!!

                            app.post(
                                "${this.url}/sync/add-to-list",
                                json = StatusRequest(
                                    shows = listOf(
                                        StatusMediaObject(
                                            null,
                                            null,
                                            ids,
                                            newStatus,
                                        )
                                    ), movies = emptyList()
                                ),
                                headers = headers
                            ).isSuccessful
                        } ?: true

                        val episodeRemovalResponse = removeEpisodes?.let { (seasons, episodes) ->
                            app.post(
                                "${this.url}/sync/history/remove",
                                json = StatusRequest(
                                    shows = listOf(
                                        HistoryMediaObject(
                                            ids = ids,
                                            seasons = seasons,
                                            episodes = episodes
                                        )
                                    ),
                                    movies = emptyList()
                                ),
                                headers = headers
                            ).isSuccessful
                        } ?: true

                        // You cannot rate if you are planning to watch it.
                        val shouldRate =
                            score != null && status != SimklListStatusType.Planning.value
                        val realScore = if (shouldRate) score else null

                        val historyResponse =
                            // Only post if there are episodes or score to upload
                            if (addEpisodes != null || shouldRate) {
                                app.post(
                                    "${this.url}/sync/history",
                                    json = StatusRequest(
                                        shows = listOf(
                                            HistoryMediaObject(
                                                null,
                                                null,
                                                ids,
                                                addEpisodes?.first,
                                                addEpisodes?.second,
                                                realScore,
                                                realScore?.let { time },
                                            )
                                        ), movies = emptyList()
                                    ),
                                    headers = headers
                                ).isSuccessful
                            } else {
                                true
                            }

                        statusResponse && episodeRemovalResponse && historyResponse
                    }
                }
            }
        }

        fun getHeaders(token: AuthToken): Map<String, String> =
            mapOf("Authorization" to "Bearer ${token.accessToken}", "simkl-api-key" to CLIENT_ID)

        suspend fun getEpisodes(
            simklId: Int?,
            type: String?,
            episodes: Int?,
            hasEnded: Boolean?
        ): Array<EpisodeMetadata>? {
            if (simklId == null) return null

            val cacheKey = "Episodes/$simklId"
            val cache = SimklCache.getKey<Array<EpisodeMetadata>>(cacheKey)

            // Return cached result if its higher or equal the amount of episodes.
            if (cache != null && cache.size >= (episodes ?: 0)) {
                return cache
            }

            // There is always one season in Anime -> no request necessary
            if (type == "anime" && episodes != null) {
                return episodes.takeIf { it > 0 }?.let {
                    (1..it).map { episode ->
                        EpisodeMetadata(
                            null, null, null, episode, null
                        )
                    }.toTypedArray()
                }
            }
            val url = when (type) {
                "anime" -> "https://api.simkl.com/anime/episodes/$simklId"
                "tv" -> "https://api.simkl.com/tv/episodes/$simklId"
                "movie" -> return null
                else -> return null
            }

            debugPrint { "Requesting episodes from $url" }
            return app.get(url, params = mapOf("client_id" to CLIENT_ID))
                .parsedSafe<Array<EpisodeMetadata>>()?.also {
                    val cacheTime =
                        if (hasEnded == true) SimklCache.CacheTimes.OneMonth.value else SimklCache.CacheTimes.ThirtyMinutes.value

                    // 1 Month cache
                    SimklCache.setKey(cacheKey, it, Duration.parse(cacheTime))
                }
        }

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        class HistoryMediaObject(
            @JsonProperty("title") title: String? = null,
            @JsonProperty("year") year: Int? = null,
            @JsonProperty("ids") ids: Ids? = null,
            @JsonProperty("seasons") seasons: List<Season>? = null,
            @JsonProperty("episodes") episodes: List<Season.Episode>? = null,
            @JsonProperty("rating") val rating: Int? = null,
            @JsonProperty("rated_at") val ratedAt: String? = null,
        ) : MediaObject(title, year, ids, seasons = seasons, episodes = episodes)

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        class RatingMediaObject(
            @JsonProperty("title") title: String?,
            @JsonProperty("year") year: Int?,
            @JsonProperty("ids") ids: Ids?,
            @JsonProperty("rating") val rating: Int,
            @JsonProperty("rated_at") val ratedAt: String? = getDateTime(unixTime)
        ) : MediaObject(title, year, ids)

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        class StatusMediaObject(
            @JsonProperty("title") title: String?,
            @JsonProperty("year") year: Int?,
            @JsonProperty("ids") ids: Ids?,
            @JsonProperty("to") val to: String,
            @JsonProperty("watched_at") val watchedAt: String? = getDateTime(unixTime)
        ) : MediaObject(title, year, ids)

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        data class StatusRequest(
            @JsonProperty("movies") val movies: List<MediaObject>,
            @JsonProperty("shows") val shows: List<MediaObject>
        )

        /** https://simkl.docs.apiary.io/#reference/sync/get-all-items/get-all-items-in-the-user's-watchlist */
        data class AllItemsResponse(
            @JsonProperty("shows")
            val shows: List<ShowMetadata> = emptyList(),
            @JsonProperty("anime")
            val anime: List<ShowMetadata> = emptyList(),
            @JsonProperty("movies")
            val movies: List<MovieMetadata> = emptyList(),
        ) {
            companion object {
                fun merge(first: AllItemsResponse?, second: AllItemsResponse?): AllItemsResponse {

                    // Replace the first item with the same id, or add the new item
                    fun <T> MutableList<T>.replaceOrAddItem(newItem: T, predicate: (T) -> Boolean) {
                        for (i in this.indices) {
                            if (predicate(this[i])) {
                                this[i] = newItem
                                return
                            }
                        }
                        this.add(newItem)
                    }

                    //
                    fun <T : Metadata> merge(
                        first: List<T>?,
                        second: List<T>?
                    ): List<T> {
                        return (first?.toMutableList() ?: mutableListOf()).apply {
                            second?.forEach { secondShow ->
                                this.replaceOrAddItem(secondShow) {
                                    it.getIds().simkl == secondShow.getIds().simkl
                                }
                            }
                        }
                    }

                    return AllItemsResponse(
                        merge(first?.shows, second?.shows),
                        merge(first?.anime, second?.anime),
                        merge(first?.movies, second?.movies),
                    )
                }
            }

            interface Metadata {
                val lastWatchedAt: String?
                val status: String?
                val userRating: Int?
                val lastWatched: String?
                val watchedEpisodesCount: Int?
                val totalEpisodesCount: Int?

                fun getIds(): ShowMetadata.Show.Ids
                fun toLibraryItem(): SyncAPI.LibraryItem
            }

            data class MovieMetadata(
                @JsonProperty("last_watched_at") override val lastWatchedAt: String?,
                @JsonProperty("status") override val status: String,
                @JsonProperty("user_rating") override val userRating: Int?,
                @JsonProperty("last_watched") override val lastWatched: String?,
                @JsonProperty("watched_episodes_count") override val watchedEpisodesCount: Int?,
                @JsonProperty("total_episodes_count") override val totalEpisodesCount: Int?,
                val movie: ShowMetadata.Show
            ) : Metadata {
                override fun getIds(): ShowMetadata.Show.Ids {
                    return this.movie.ids
                }

                override fun toLibraryItem(): SyncAPI.LibraryItem {
                    return SyncAPI.LibraryItem(
                        this.movie.title,
                        "https://simkl.com/tv/${movie.ids.simkl}",
                        movie.ids.simkl.toString(),
                        this.watchedEpisodesCount,
                        this.totalEpisodesCount,
                        Score.from10(this.userRating),
                        getUnixTime(lastWatchedAt) ?: 0,
                        "Simkl",
                        TvType.Movie,
                        this.movie.poster?.let { getPosterUrl(it) },
                        null,
                        null,
                        this.movie.year?.toYear(),
                        movie.ids.simkl
                    )
                }
            }

            data class ShowMetadata(
                @JsonProperty("last_watched_at") override val lastWatchedAt: String?,
                @JsonProperty("status") override val status: String,
                @JsonProperty("user_rating") override val userRating: Int?,
                @JsonProperty("last_watched") override val lastWatched: String?,
                @JsonProperty("watched_episodes_count") override val watchedEpisodesCount: Int?,
                @JsonProperty("total_episodes_count") override val totalEpisodesCount: Int?,
                @JsonProperty("show") val show: Show
            ) : Metadata {
                override fun getIds(): Show.Ids {
                    return this.show.ids
                }

                override fun toLibraryItem(): SyncAPI.LibraryItem {
                    return SyncAPI.LibraryItem(
                        this.show.title,
                        "https://simkl.com/tv/${show.ids.simkl}",
                        show.ids.simkl.toString(),
                        this.watchedEpisodesCount,
                        this.totalEpisodesCount,
                        Score.from10(this.userRating),
                        getUnixTime(lastWatchedAt) ?: 0,
                        "Simkl",
                        TvType.Anime,
                        this.show.poster?.let { getPosterUrl(it) },
                        null,
                        null,
                        this.show.year?.toYear(),
                        show.ids.simkl
                    )
                }

                data class Show(
                    @JsonProperty("title") val title: String,
                    @JsonProperty("poster") val poster: String?,
                    @JsonProperty("year") val year: Int?,
                    @JsonProperty("ids") val ids: Ids,
                ) {
                    data class Ids(
                        @JsonProperty("simkl") val simkl: Int,
                        @JsonProperty("slug") val slug: String?,
                        @JsonProperty("imdb") val imdb: String?,
                        @JsonProperty("zap2it") val zap2it: String?,
                        @JsonProperty("tmdb") val tmdb: String?,
                        @JsonProperty("offen") val offen: String?,
                        @JsonProperty("tvdb") val tvdb: String?,
                        @JsonProperty("mal") val mal: String?,
                        @JsonProperty("anidb") val anidb: String?,
                        @JsonProperty("anilist") val anilist: String?,
                        @JsonProperty("traktslug") val traktslug: String?
                    ) {
                        fun matchesId(database: SimklSyncServices, id: String): Boolean {
                            return when (database) {
                                SimklSyncServices.Simkl -> this.simkl == id.toIntOrNull()
                                SimklSyncServices.AniList -> this.anilist == id
                                SimklSyncServices.Mal -> this.mal == id
                                SimklSyncServices.Tmdb -> this.tmdb == id
                                SimklSyncServices.Imdb -> this.imdb == id
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Appends api keys to the requests
     **/
    /*private inner class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            debugPrint { "${this@SimklApi.name} made request to ${chain.request().url}" }
            return chain.proceed(
                chain.request()
                    .newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("simkl-api-key", CLIENT_ID)
                    .build()
            )
        }
    }*/

    private suspend fun getUser(token: AuthToken): SettingsResponse =
        app.post("$mainUrl/users/settings", headers = getHeaders(token))
            .parsed<SettingsResponse>()


    /**
     * Useful to get episodes on demand to prevent unnecessary requests.
     */
    class SimklEpisodeConstructor(
        private val simklId: Int?,
        private val type: String?,
        private val totalEpisodeCount: Int?,
        private val hasEnded: Boolean?
    ) {
        suspend fun getEpisodes(): Array<EpisodeMetadata>? {
            return getEpisodes(simklId, type, totalEpisodeCount, hasEnded)
        }
    }

    class SimklSyncStatus(
        override var status: SyncWatchType,
        override var score: Score?,
        val oldScore: Int?,
        override var watchedEpisodes: Int?,
        val episodeConstructor: SimklEpisodeConstructor,
        override var isFavorite: Boolean? = null,
        override var maxEpisodes: Int? = null,
        override var startDate: Long? = null,
        override var endDate: Long? = null,
        /** Save seen episodes separately to know the change from old to new.
         * Required to remove seen episodes if count decreases */
        val oldEpisodes: Int,
        val oldStatus: String?
    ) : SyncAPI.AbstractSyncStatus()

    /**
     * Internal method to resolve Simkl ID from cross-references with timeout and fallback.
     * Implements the refactor from SyncViewModel to provider level.
     */
    private suspend fun resolveSimklIdInternal(id: String, title: String? = null): String? {
        return try {
            kotlinx.coroutines.withTimeout<String?>(5000) {
                android.util.Log.d("[SIMKL_ID_RESOLVE]", "Resolving ID: $id")
                
                // Check if ID is JSON cross-ref format (contains {)
                if (id.contains("{")) {
                    val realIds = readIdFromString(id)
                    android.util.Log.d("[SIMKL_ID_RESOLVE]", "Cross-ref IDs detected: $realIds")
                    
                    // Try /search/id endpoint first
                    val idMap = mutableMapOf<String, String>()
                    realIds.forEach { (service, serviceId) ->
                        when (service) {
                            SimklSyncServices.Mal -> idMap["mal"] = serviceId
                            SimklSyncServices.AniList -> idMap["anilist"] = serviceId
                            else -> {}
                        }
                    }
                    
                    if (idMap.isNotEmpty()) {
                        android.util.Log.d("[SIMKL_ID_RESOLVE]", "Trying /search/id with: $idMap")
                        val results = searchById(idMap)
                        val simklId = results?.firstOrNull()?.id
                        
                        if (simklId != null) {
                            android.util.Log.d("[SIMKL_ID_RESOLVE]", "Resolved Simkl ID via /search/id: $simklId")
                            return@withTimeout simklId.toString()
                        } else {
                            android.util.Log.w("[SIMKL_ID_RESOLVE]", "/search/id failed, trying title fallback")
                            
                            // Fallback to title search
                            title?.let { searchTitle ->
                                android.util.Log.d("[SIMKL_ID_RESOLVE]", "Trying title search for: $searchTitle")
                                val searchResults = search(null, searchTitle)
                                val titleSimklId = searchResults?.firstOrNull()?.id
                                
                                if (titleSimklId != null) {
                                    android.util.Log.d("[SIMKL_ID_RESOLVE]", "Resolved Simkl ID via title search: $titleSimklId")
                                    return@withTimeout titleSimklId.toString()
                                }
                            }
                        }
                    }
                } else {
                    // Direct numeric ID - no resolution needed
                    android.util.Log.d("[SIMKL_ID_RESOLVE]", "Direct numeric ID, no resolution needed: $id")
                    return@withTimeout id
                }
                
                android.util.Log.w("[SIMKL_ID_RESOLVE]", "All resolution methods failed")
                null
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("[SIMKL_ID_RESOLVE]", "Resolution timeout after 5 seconds, proceeding with tentative ID")
            id // Return original ID as "tentative" for retry on next app launch
        } catch (e: Exception) {
            android.util.Log.e("[SIMKL_ID_RESOLVE]", "Resolution error: ${e.message}", e)
            null
        }
    }

    override suspend fun status(auth: AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        if (auth == null) return null
        
        // [SIMKL_ID_REFACTOR] Resolve Simkl ID at provider level with fallback and timeout
        val resolvedId = resolveSimklIdInternal(id)
        val finalId = resolvedId ?: id
        
        android.util.Log.d("[SIMKL_ID_REFACTOR]", "Status called with id: $id, resolved to: $finalId")
        
        val realIds = readIdFromString(id)

        // [SIMKL_BUG_FIX] Check if id is a direct numeric Simkl ID (new format) or JSON cross-refs (old format)
        val isDirectSimklId = finalId.toIntOrNull() != null
        
        val searchResult: MediaObject = if (isDirectSimklId) {
            // New format: Direct Simkl ID - need to fetch metadata
            android.util.Log.d("[SIMKL_ID_FIX]", "Using direct Simkl ID: $finalId")
            val idKey = "simkl=$finalId"
            val cachedObject = SimklCache.getKey<MediaObject>(idKey)
            cachedObject ?: (searchByIds(mapOf(SimklSyncServices.Simkl to finalId))?.firstOrNull()?.also { result ->
                val cacheTime =
                    if (result.hasEnded()) SimklCache.CacheTimes.OneMonth.value else SimklCache.CacheTimes.ThirtyMinutes.value
                SimklCache.setKey(idKey, result, Duration.parse(cacheTime))
            }) ?: return null
        } else {
            // Old format: JSON cross-refs - use existing logic
            android.util.Log.d("[SIMKL_ID_FIX]", "Using cross-ref IDs: $realIds")
            
            // Key which assumes all ids are the same each time :/
            // This could be some sort of reference system to make multiple IDs
            // point to the same key.
            val idKey =
                realIds.toList().map { "${it.first.originalName}=${it.second}" }.sorted().joinToString()

            val cachedObject = SimklCache.getKey<MediaObject>(idKey)
            cachedObject ?: (searchByIds(realIds)?.firstOrNull()?.also { result ->
                val cacheTime =
                    if (result.hasEnded()) SimklCache.CacheTimes.OneMonth.value else SimklCache.CacheTimes.ThirtyMinutes.value
                SimklCache.setKey(idKey, result, Duration.parse(cacheTime))
            }) ?: return null
        }

        val episodeConstructor = SimklEpisodeConstructor(
            searchResult.ids?.simkl,
            searchResult.type,
            searchResult.totalEpisodes,
            searchResult.hasEnded()
        )

        val foundItem = getSyncListSmart(auth)?.let { list ->
            listOf(list.shows, list.anime, list.movies).flatten().firstOrNull { show ->
                realIds.any { (database, id) ->
                    show.getIds().matchesId(database, id)
                }
            }
        }

        if (foundItem != null) {
            return SimklSyncStatus(
                status = foundItem.status?.let {
                    SyncWatchType.fromInternalId(
                        SimklListStatusType.fromString(
                            it
                        )?.value
                    )
                }
                    ?: return null,
                score = Score.from10(foundItem.userRating),
                watchedEpisodes = foundItem.watchedEpisodesCount,
                maxEpisodes = searchResult.totalEpisodes,
                episodeConstructor = episodeConstructor,
                oldEpisodes = foundItem.watchedEpisodesCount ?: 0,
                oldScore = foundItem.userRating,
                oldStatus = foundItem.status
            )
        } else {
            return SimklSyncStatus(
                status = SyncWatchType.fromInternalId(SimklListStatusType.None.value),
                score = null,
                watchedEpisodes = 0,
                maxEpisodes = if (searchResult.type == "movie") 0 else searchResult.totalEpisodes,
                episodeConstructor = episodeConstructor,
                oldEpisodes = 0,
                oldStatus = null,
                oldScore = null
            )
        }
    }

    override suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus
    ): Boolean {
        // [SIMKL_BUG_FIX] Check if id is a direct numeric Simkl ID (new format) or JSON cross-refs (old format)
        val isDirectSimklId = id.toIntOrNull() != null
        val parsedId = if (isDirectSimklId) {
            android.util.Log.d("[SIMKL_ID_FIX]", "updateStatus using direct Simkl ID: $id")
            mapOf(SimklSyncServices.Simkl to id)
        } else {
            android.util.Log.d("[SIMKL_ID_FIX]", "updateStatus using cross-ref IDs: $id")
            readIdFromString(id)
        }
        
        lastScoreTime = unixTime
        val simklStatus = newStatus as? SimklSyncStatus

        val builder = SimklScoreBuilder.Builder()
            .apiUrl(this.mainUrl)
            .score(newStatus.score?.toInt(10), simklStatus?.oldScore)
            .status(
                newStatus.status.internalId,
                (newStatus as? SimklSyncStatus)?.oldStatus?.let { oldStatus ->
                    SimklListStatusType.entries.firstOrNull {
                        it.originalName == oldStatus
                    }?.value
                })
            .token(auth?.token ?: return false)
            .ids(MediaObject.Ids.fromMap(parsedId))


        // Get episodes only when required
        val episodes = simklStatus?.episodeConstructor?.getEpisodes()

        // All episodes if marked as completed
        val watchedEpisodes =
            if (newStatus.status.internalId == SimklListStatusType.Completed.value) {
                episodes?.size
            } else {
                newStatus.watchedEpisodes
            }

        builder.episodes(episodes?.toList(), watchedEpisodes, simklStatus?.oldEpisodes)

        requireLibraryRefresh = true
        return builder.execute()
    }


    /** See https://simkl.docs.apiary.io/#reference/search/id-lookup/get-items-by-id */
    private suspend fun searchByIds(serviceMap: Map<SimklSyncServices, String>): Array<MediaObject>? {
        if (serviceMap.isEmpty()) return emptyArray()

        // [SIMKL_DEFINITIVE_FIX][PHASE2+4+5] ID-based lookup with rate limiting and error handling
        android.util.Log.d("[SIMKL_API]", "searchByIds called with: $serviceMap")

        val result = ErrorHandler.executeWithRetry(rateLimiter) {
            val response = app.get(
                "$mainUrl/search/id",
                params = mapOf("client_id" to CLIENT_ID) + serviceMap.map { (service, id) ->
                    service.originalName to id
                }
            )

            if (response.isSuccessful) {
                response.parsedSafe<Array<MediaObject>>()
            } else {
                throw Exception("HTTP ${response.code}: Search by IDs failed")
            }
        }

        return if (result.isSuccess) {
            android.util.Log.d("[SIMKL_API]", "searchByIds success: found ${result.data?.size ?: 0} items")
            result.data
        } else {
            android.util.Log.e("[SIMKL_API]", "searchByIds failed after retries: ${result.errorCode}")
            null
        }
    }

    /**
     * Public ID-based search method for external callers.
     * Prioritize this over title search when MAL/AniList IDs are available.
     * [SIMKL_DEFINITIVE_FIX][PHASE2]
     */
    suspend fun searchById(idMap: Map<String, String>): List<SyncAPI.SyncSearchResult>? {
        val serviceMap = idMap.mapNotNull { (key, value) ->
            when (key.lowercase()) {
                "mal" -> SimklSyncServices.Mal to value
                "anilist" -> SimklSyncServices.AniList to value
                "imdb" -> SimklSyncServices.Imdb to value
                "tmdb" -> SimklSyncServices.Tmdb to value
                else -> null
            }
        }.toMap()

        return searchByIds(serviceMap)?.mapNotNull { it.toSyncSearchResult() }
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        // [SIMKL_DEFINITIVE_FIX][PHASE2+4+5] Fixed bug, added rate limiting and error handling
        android.util.Log.d("[SIMKL_API]", "search called with query: $query")

        val result = ErrorHandler.executeWithRetry(rateLimiter) {
            val response = app.get(
                "$mainUrl/search/", params = mapOf("client_id" to CLIENT_ID, "q" to query)
            )

            if (response.isSuccessful) {
                response.parsedSafe<Array<MediaObject>>()?.mapNotNull { it.toSyncSearchResult() }
            } else {
                throw Exception("HTTP ${response.code}: Search failed")
            }
        }

        return if (result.isSuccess) {
            result.data
        } else {
            android.util.Log.e("[SIMKL_API]", "search failed after retries: ${result.errorCode}")
            null
        }
    }

    override fun loginRequest(): AuthLoginPage? {
        val lastLoginState = BigInteger(130, SecureRandom()).toString(32)
        val url =
            "https://simkl.com/oauth/authorize?response_type=code&client_id=$CLIENT_ID&redirect_uri=$APP_STRING://${redirectUrlIdentifier}&state=$lastLoginState"

        return AuthLoginPage(
            url = url,
            payload = lastLoginState
        )
    }

    override suspend fun load(auth: AuthData?, id: String): SyncResult? {
        val simklId = id.toIntOrNull() ?: return null
        return try {
            rateLimiter.acquire()
            // Detail endpoints are /tv/{id} and /anime/{id}; the sync ID alone
            // doesn't encode the media type, so try TV first, then anime.
            val summary = run {
                val tv = app.get(
                    "https://api.simkl.com/tv/$simklId",
                    params = mapOf("client_id" to CLIENT_ID)
                ).parsedSafe<SimklSummary>()
                if (tv != null && tv.title != null) {
                    tv
                } else {
                    rateLimiter.acquire()
                    app.get(
                        "https://api.simkl.com/anime/$simklId",
                        params = mapOf("client_id" to CLIENT_ID)
                    ).parsedSafe<SimklSummary>()
                }
            } ?: return null

            SyncResult(
                id = id,
                title = summary.title,
                publicScore = summary.ratings?.simkl?.rating?.let { Score.from(it, 10) },
                genres = summary.genres,
                totalEpisodes = summary.totalEpisodes,
                synopsis = summary.overview,
                airStatus = when (summary.status?.lowercase()) {
                    "airing" -> ShowStatus.Ongoing
                    "ended" -> ShowStatus.Completed
                    else -> null
                },
                posterUrl = summary.poster?.let { getPosterUrl(it) },
                startDate = parseSimklDate(summary.firstAired),
                endDate = parseSimklDate(summary.lastAired),
            )
        } catch (e: Exception) {
            android.util.Log.e("[SIMKL_API]", "load failed: ${e.message}", e)
            null
        }
    }

    private fun parseSimklDate(date: String?): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .parse(date ?: return null)?.time
    } catch (e: Exception) {
        null
    }

    data class SimklSummary(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
        @JsonProperty("first_aired") val firstAired: String? = null,
        @JsonProperty("last_aired") val lastAired: String? = null,
        @JsonProperty("ratings") val ratings: SimklRatings? = null,
    ) {
        data class SimklRatings(
            @JsonProperty("simkl") val simkl: SimklRating? = null,
        )

        data class SimklRating(
            @JsonProperty("rating") val rating: Float? = null,
        )
    }

    private suspend fun getSyncListSince(auth: AuthData, since: Long?): AllItemsResponse? {
        val params = getDateTime(since)?.let {
            mapOf("date_from" to it)
        } ?: emptyMap()

        // [SIMKL_DEFINITIVE_FIX][PHASE4] Apply rate limiting
        rateLimiter.acquire()

        // Can return null on no change.
        return app.get(
            "$mainUrl/sync/all-items/",
            params = params,
            headers = getHeaders(auth.token)
        ).parsedSafe()
    }

    private suspend fun getActivities(token: AuthToken): ActivitiesResponse? {
        // [SIMKL_DEFINITIVE_FIX][PHASE4] Apply rate limiting
        rateLimiter.acquire()

        return app.post("$mainUrl/sync/activities", headers = getHeaders(token)).parsedSafe()
    }

    private fun getSyncListCached(auth: AuthData): AllItemsResponse? {
        return getKey<AllItemsResponse>(SIMKL_CACHED_LIST, auth.user.id.toString())
    }

    private suspend fun getSyncListSmart(auth: AuthData): AllItemsResponse? {
        val activities = getActivities(auth.token)
        val userId = auth.user.id.toString()
        val lastCacheUpdate = getKey<Long>(SIMKL_CACHED_LIST_TIME, auth.user.id.toString())
        val lastRemoval = listOf(
            activities?.tvShows?.removedFromList,
            activities?.anime?.removedFromList,
            activities?.movies?.removedFromList
        ).maxOf {
            getUnixTime(it) ?: -1
        }
        val lastRealUpdate =
            listOf(
                activities?.tvShows?.all,
                activities?.anime?.all,
                activities?.movies?.all,
            ).maxOf {
                getUnixTime(it) ?: -1
            }

        debugPrint { "Cache times: lastCacheUpdate=$lastCacheUpdate, lastRemoval=$lastRemoval, lastRealUpdate=$lastRealUpdate" }
        val list = if (lastCacheUpdate == null || lastCacheUpdate < lastRemoval) {
            debugPrint { "Full list update in ${this.name}." }
            setKey(SIMKL_CACHED_LIST_TIME, userId, lastRemoval)
            getSyncListSince(auth, null)
        } else if (lastCacheUpdate < lastRealUpdate || lastCacheUpdate < lastScoreTime) {
            debugPrint { "Partial list update in ${this.name}." }
            setKey(SIMKL_CACHED_LIST_TIME, userId, lastCacheUpdate)
            AllItemsResponse.merge(
                getSyncListCached(auth),
                getSyncListSince(auth, lastCacheUpdate)
            )
        } else {
            debugPrint { "Cached list update in ${this.name}." }
            getSyncListCached(auth)
        }
        debugPrint { "List sizes: movies=${list?.movies?.size}, shows=${list?.shows?.size}, anime=${list?.anime?.size}" }

        setKey(SIMKL_CACHED_LIST, userId, list)

        return list
    }

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val list = getSyncListSmart(auth ?: return null) ?: return null

        val baseMap =
            SimklListStatusType.entries
                .filter { it.value >= 0 && it.value != SimklListStatusType.ReWatching.value }
                .associate {
                    it.stringRes to emptyList<SyncAPI.LibraryItem>()
                }

        val syncMap = listOf(list.anime, list.movies, list.shows)
            .flatten()
            .groupBy {
                it.status
            }
            .mapNotNull { (status, list) ->
                val stringRes =
                    status?.let { SimklListStatusType.fromString(it)?.stringRes }
                        ?: return@mapNotNull null
                val libraryList = list.map { it.toLibraryItem() }
                stringRes to libraryList
            }.toMap()

        return SyncAPI.LibraryMetadata(
            (baseMap + syncMap).map { SyncAPI.LibraryList(txt(it.key), it.value) }, setOf(
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.ReleaseDateNew,
                ListSorting.ReleaseDateOld,
                ListSorting.RatingHigh,
                ListSorting.RatingLow,
            )
        )
    }

    override fun urlToId(url: String): String? {
        val simklUrlRegex = Regex("""https://simkl\.com/[^/]*/(\d+).*""")
        return simklUrlRegex.find(url)?.groupValues?.get(1) ?: ""
    }

    override suspend fun pinRequest(): AuthPinData? {
        val pinAuthResp = app.get(
            "$mainUrl/oauth/pin?client_id=$CLIENT_ID&redirect_uri=$APP_STRING://${redirectUrlIdentifier}"
        ).parsedSafe<PinAuthResponse>() ?: return null

        return AuthPinData(
            deviceCode = pinAuthResp.deviceCode,
            userCode = pinAuthResp.userCode,
            verificationUrl = pinAuthResp.verificationUrl,
            expiresIn = pinAuthResp.expiresIn,
            interval = pinAuthResp.interval
        )
    }

    override suspend fun login(payload: AuthPinData): AuthToken? {
        val pinAuthResp = app.get(
            "$mainUrl/oauth/pin/${payload.userCode}?client_id=$CLIENT_ID"
        ).parsedSafe<PinExchangeResponse>() ?: return null

        return AuthToken(
            accessToken = pinAuthResp.accessToken ?: return null,
        )
    }

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val uri = redirectUrl.toUri()
        val state = uri.getQueryParameter("state")
        // Ensure consistent state
        if (state != payload) return null

        val code = uri.getQueryParameter("code") ?: return null
        val tokenResponse = app.post(
            "$mainUrl/oauth/token", json = TokenRequest(code)
        ).parsedSafe<TokenResponse>() ?: return null

        return AuthToken(
            accessToken = tokenResponse.accessToken,
        )
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null)
        return AuthUser(
            id = user.account.id,
            name = user.user.name,
            profilePicture = user.user.avatar
        )
    }
}
