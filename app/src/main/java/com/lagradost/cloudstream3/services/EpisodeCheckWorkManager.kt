package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toUri
import androidx.work.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.getApiFromUrlNull
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.getAiredLatestEpisodes
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.DownloadPreferences
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.EPISODE_PARENT_INDEX
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import android.os.StatFs
import android.net.ConnectivityManager
import androidx.preference.PreferenceManager
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

typealias SubscribedData = com.lagradost.cloudstream3.utils.DataStoreHelper.SubscribedData

const val EPISODE_CHECK_CHANNEL_ID = "cloudstream3.episode_check"
const val EPISODE_CHECK_WORK_NAME = "work_episode_check"
const val EPISODE_CHECK_MANUAL_TAG = "work_episode_check_manual"
const val EPISODE_CHECK_CHANNEL_NAME = "Episode Check"
const val EPISODE_CHECK_CHANNEL_DESCRIPTION = "Notifications for new episodes in ongoing series"
const val EPISODE_CHECK_NOTIFICATION_ID = 938712898 // Random unique

class EpisodeCheckWorkManager(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        fun enqueuePeriodicWork(context: Context?, intervalHours: Int = 12) {
            if (context == null) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSyncDataWork =
                PeriodicWorkRequest.Builder(EpisodeCheckWorkManager::class.java, intervalHours.toLong(), TimeUnit.HOURS)
                    .addTag(EPISODE_CHECK_WORK_NAME)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                EPISODE_CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicSyncDataWork
            )
        }

        fun triggerManualCheck(context: Context?) {
            if (context == null) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeWorkRequest =
                OneTimeWorkRequest.Builder(EpisodeCheckWorkManager::class.java)
                    .addTag(EPISODE_CHECK_WORK_NAME)
                    .addTag(EPISODE_CHECK_MANUAL_TAG)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
            android.util.Log.d("EpisodeCheck", "[MANUAL_TRIGGER] Manual episode check triggered")
        }

        fun cancelWork(context: Context?) {
            if (context == null) return
            WorkManager.getInstance(context).cancelUniqueWork(EPISODE_CHECK_WORK_NAME)
        }
    }

    private val progressNotificationBuilder =
        NotificationCompat.Builder(context, EPISODE_CHECK_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(context.getString(R.string.subscription_in_progress_notification))
            .setSmallIcon(com.google.android.gms.cast.framework.R.drawable.quantum_ic_refresh_white_24)
            .setProgress(0, 0, true)

    // Fresh builder per notification: the previous shared builder was mutated by
    // concurrent showNotification/showAutoDownloadNotification/showCompletionNotification
    // calls (amap loop), so built notifications lost title/largeIcon/smallIcon
    private fun baseNotificationBuilder() =
        NotificationCompat.Builder(context, EPISODE_CHECK_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateProgress(max: Int, progress: Int, indeterminate: Boolean) {
        notificationManager.notify(
            EPISODE_CHECK_NOTIFICATION_ID, progressNotificationBuilder
                .setProgress(max, progress, indeterminate)
                .build()
        )
    }

    @Suppress("DEPRECATION_ERROR")
    override suspend fun doWork(): Result {
        // Check if episode checking is enabled
        if (!DataStoreHelper.episodeCheckEnabled) {
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_DISABLED] Episode checking is disabled, skipping")
            return Result.success()
        }
        
        try {
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Episode check work started")
            context.createNotificationChannel(
                EPISODE_CHECK_CHANNEL_ID,
                EPISODE_CHECK_CHANNEL_NAME,
                EPISODE_CHECK_CHANNEL_DESCRIPTION
            )

            val foregroundInfo = if (SDK_INT >= 29)
                ForegroundInfo(
                    EPISODE_CHECK_NOTIFICATION_ID,
                    progressNotificationBuilder.build(),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
                ) else ForegroundInfo(EPISODE_CHECK_NOTIFICATION_ID, progressNotificationBuilder.build(),)
            setForeground(foregroundInfo)
            
            val subscriptions = DataStoreHelper.getAllSubscriptions()
            if (subscriptions.isEmpty()) {
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_SKIP] No subscriptions found, showing info notification")
                showCompletionNotification(0, 0, 0)
                return Result.success()
            }
            
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Found ${subscriptions.size} subscriptions to check")
            
            // Load plugins (required for API calls)
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Loading plugins")
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context)
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context, false)
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Plugins loaded successfully")
            
            val max = subscriptions.size
            var progress = 0
            var checked = 0
            var apiFailures = 0
            var newEpisodesFound = 0
            val failureReasons = mutableListOf<String>()
            updateProgress(max, progress, true)
            
            subscriptions.amap { subscription ->
                try {
                    val result = checkSingleSubscription(subscription)
                    when (result) {
                        is CheckResult.NewEpisodes -> newEpisodesFound++
                        is CheckResult.ApiFailure -> {
                            apiFailures++
                            failureReasons += result.reason
                        }
                        is CheckResult.NoChange -> { /* no-op */ }
                    }
                    checked++
                    updateProgress(max, ++progress, false)
                } catch (t: Throwable) {
                    android.util.Log.e("EpisodeCheck", "[EPISODE_CHECK_ERROR] Failed checking: ${subscription.name}", t)
                    apiFailures++
                    failureReasons += t.message ?: "Unknown error"
                    // Continue with other subscriptions, don't fail entire work
                }
            }
            
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_COMPLETE] Checked: $checked, Failures: $apiFailures, New episodes: $newEpisodesFound, Reasons: $failureReasons")
            
            // Show completion notification
            showCompletionNotification(checked, apiFailures, newEpisodesFound, failureReasons)
            
            return Result.success()
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[EPISODE_CHECK_FATAL] Episode check failed", t)
            logError(t)

            // Return success instead of retry: retrying with exponential backoff on a
            // persistent error would stall all episode checks for hours. The next
            // periodic run or manual trigger will try again.
            return Result.success()
        }
    }

    private sealed class CheckResult {
        data object NoChange : CheckResult()
        data class NewEpisodes(val count: Int) : CheckResult()
        data class ApiFailure(val reason: String) : CheckResult()
    }

    private suspend fun checkSingleSubscription(subscription: SubscribedData): CheckResult {
        val id = subscription.id ?: return CheckResult.ApiFailure("No id stored")
        val api = getApiFromNameNull(subscription.apiName) ?: getApiFromUrlNull(subscription.url) ?: run {
            android.util.Log.w("EpisodeCheck", "[EPISODE_CHECK_SKIP] API not found: ${subscription.apiName} (url: ${subscription.url})")
            return CheckResult.ApiFailure("Provider '${subscription.apiName}' not installed")
        }
        
        android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Checking: ${subscription.name}")
        
        // Fetch fresh data from API with timeout
        val response = withTimeoutOrNull(60_000) {
            api.load(subscription.url) as? EpisodeResponse
        }
        
        if (response == null) {
            android.util.Log.w("EpisodeCheck", "[EPISODE_CHECK_SKIP] No response for: ${subscription.name}")
            return CheckResult.ApiFailure("No response from ${subscription.apiName}")
        }
        
        // Get latest episode counts per dub status, ignoring unaired/planned episodes.
        // Values are encoded (season * 1_000_000 + episode) so per-season-numbered
        // shows stay monotonic across seasons — a new season's episode 1 must
        // compare greater than the previous season's episode 25.
        val latestEpisodes = response.getAiredLatestEpisodes()
        val lastSeen = subscription.lastSeenEpisodeCount
        
        android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_DATA] Latest episodes: $latestEpisodes, Last seen: $lastSeen")
        
        // Check for new episodes across all dub statuses
        val newEpisodesByStatus = mutableMapOf<DubStatus, Int>()
        
        DubStatus.entries.forEach { status ->
            val latest = latestEpisodes[status] ?: return@forEach
            val seen = lastSeen[status] ?: 0
            
            if (latest > seen) {
                newEpisodesByStatus[status] = latest
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_FOUND] New episodes for ${subscription.name}: $status $seen -> $latest")
            }
        }
        
        // Renew the cached entry while the fresh data is in hand — header count,
        // season metadata, per-episode cache and the parent index, so the
        // offline/cached view shows the new episodes without a force-refresh.
        updateCachedEpisodeCount(id, response, newEpisodesByStatus.keys)
        
        if (newEpisodesByStatus.isEmpty()) return CheckResult.NoChange
        
        // Notify or auto-download for each dub status with new episodes.
        // The displayed/looked-up episode is the RAW number, not the encoded value.
        var allHandled = true
        newEpisodesByStatus.forEach { (status, latest) ->
            val latestEpisode = latestAiredEpisodeNumber(response, status) ?: (latest % 1_000_000)
            val handled = handleNewEpisodes(subscription, response, status, latestEpisode)
            if (!handled) allHandled = false
        }
        
        // Only update subscription tracking if all episodes were handled successfully
        if (allHandled) {
            DataStoreHelper.updateSubscribedData(id, subscription, response)
        }
        
        return CheckResult.NewEpisodes(newEpisodesByStatus.values.sum())
    }

    /** Raw episode number of the latest aired episode for [status] — what the
     *  notification shows and the auto-download looks up. Mirrors the encoding
     *  used by getAiredLatestEpisodes without exposing the encoded value. */
    private fun latestAiredEpisodeNumber(
        response: EpisodeResponse,
        status: DubStatus
    ): Int? {
        val list = when (response) {
            is AnimeLoadResponse -> response.episodes[status].orEmpty()
            is TvSeriesLoadResponse -> response.episodes
            else -> return null
        }
        val now = System.currentTimeMillis()
        val aired = list.filter { ep ->
            val d = ep.date
            d == null || d <= now
        }
        val considered = if (aired.isEmpty()) list else aired
        return considered.maxByOrNull {
            (it.season ?: 1) * 1_000_000 + (it.episode ?: 0)
        }?.episode
    }

    /**
     * Renews the cached entry for a subscribed show while fresh data is present:
     * header episode count, per-season metadata, per-episode cache entries and
     * the parent index — so the offline/cached result view reflects the new
     * episodes without the user having to force-refresh.
     */
    private fun updateCachedEpisodeCount(
        id: Int,
        response: EpisodeResponse,
        newStatuses: Set<DubStatus>
    ) {
        val cacheKey = CloudStreamApp.getKeys(DOWNLOAD_HEADER_CACHE)
            ?.find { key ->
                val header = CloudStreamApp.getKey<DownloadHeaderCached>(key)
                header?.id == id
            } ?: return
        val existing = CloudStreamApp.getKey<DownloadHeaderCached>(cacheKey) ?: return

        // Total listed episode count — matches the result-page cache semantics
        val episodeCount = when (response) {
            is AnimeLoadResponse -> response.episodes.values.flatten().size
            is TvSeriesLoadResponse -> response.episodes.size
            else -> null
        }

        val seasonMeta = existing.seasonMetadata?.toMutableMap() ?: mutableMapOf()
        val newIds = mutableListOf<String>()
        fun mergeSeason(season: Int?, episodeNumber: Int) {
            val key = season ?: 1
            seasonMeta[key] = seasonMeta[key]?.let { meta ->
                meta.copy(
                    episodeCount = maxOf(meta.episodeCount, episodeNumber),
                    episodes = (meta.episodes + episodeNumber).distinct().sorted()
                )
            } ?: DownloadObjects.SeasonMetadata(
                episodeCount = episodeNumber,
                episodes = listOf(episodeNumber)
            )
        }

        when (response) {
            is AnimeLoadResponse -> response.episodes.forEach { (dubStatus, list) ->
                if (newStatuses.isNotEmpty() && dubStatus !in newStatuses) return@forEach
                list.forEachIndexed { index, ep ->
                    val episodeNumber = ep.episode ?: (index + 1)
                    val epId = id + episodeNumber + dubStatus.id * 1_000_000 +
                            (ep.season?.times(10_000) ?: 0)
                    writeEpisodeCache(id, epId, ep, episodeNumber, dubStatus.name, response)
                    newIds += epId.toString()
                    mergeSeason(ep.season, episodeNumber)
                }
            }

            is TvSeriesLoadResponse -> {
                if (newStatuses.isEmpty() || DubStatus.None in newStatuses) {
                    response.episodes.forEachIndexed { index, ep ->
                        val episodeNumber = ep.episode ?: (index + 1)
                        val epId = id + (ep.season?.times(100_000) ?: 0) + episodeNumber + 1
                        writeEpisodeCache(id, epId, ep, episodeNumber, "None", response)
                        newIds += epId.toString()
                        mergeSeason(ep.season, episodeNumber)
                    }
                }
            }

            else -> {}
        }

        if (newIds.isNotEmpty()) {
            val indexKey = "${EPISODE_PARENT_INDEX}_$id"
            val currentIds = CloudStreamApp.getKey<Set<String>>(indexKey) ?: emptySet()
            CloudStreamApp.setKey(indexKey, currentIds + newIds)
        }

        val updated = existing.copy(
            episodeCount = episodeCount ?: existing.episodeCount,
            totalSeasons = seasonMeta.keys.maxOrNull()?.let { maxSeason ->
                maxOf(existing.totalSeasons ?: 0, maxSeason)
            } ?: existing.totalSeasons,
            seasonMetadata = seasonMeta.ifEmpty { existing.seasonMetadata },
            cacheTime = System.currentTimeMillis()
        )
        CloudStreamApp.setKey(DOWNLOAD_HEADER_CACHE, cacheKey, updated)
        android.util.Log.d(
            "EpisodeCheck",
            "[EPISODE_CHECK_CACHE] Renewed cache for ${existing.name}: $episodeCount episodes, " +
                    "${newIds.size} episodes cached, ${seasonMeta.size} seasons"
        )
    }

    private fun writeEpisodeCache(
        parentId: Int,
        id: Int,
        episode: Episode,
        episodeNumber: Int,
        dubStatus: String,
        response: EpisodeResponse
    ) {
        val seasonData = response.seasonNames?.firstOrNull { it.season == episode.season }
        val episodeCached = DownloadObjects.DownloadEpisodeCached(
            name = episode.name,
            poster = episode.posterUrl,
            episode = episodeNumber,
            season = episode.season,
            id = id,
            parentId = parentId,
            score = episode.score,
            description = episode.description,
            date = episode.date,
            cacheTime = System.currentTimeMillis(),
            dubStatus = dubStatus,
            data = episode.data,
            totalEpisodeIndex = episode.season?.let {
                response.getTotalEpisodeIndex(episodeNumber, it)
            },
            displaySeason = seasonData?.displaySeason ?: episode.season,
            runTime = episode.runTime,
            isFiller = null
        )
        CloudStreamApp.setKey(DOWNLOAD_EPISODE_CACHE, id.toString(), episodeCached)
    }

    private suspend fun handleNewEpisodes(
        subscription: SubscribedData,
        response: EpisodeResponse,
        dubStatus: DubStatus,
        latestEpisode: Int
    ): Boolean {
        val shouldAutoDownload = DataStoreHelper.autoDownloadSubscribedEpisodes
        
        return if (shouldAutoDownload) {
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_TRIGGER] Auto-downloading ${subscription.name} ep $latestEpisode ($dubStatus)")
            autoDownloadEpisode(subscription, response, dubStatus, latestEpisode)
        } else {
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_NOTIFY] Showing notification for ${subscription.name} ep $latestEpisode")
            showNotification(subscription, response, latestEpisode, dubStatus)  // Pass dubStatus
            true // Notifications are always "successful"
        }
    }

    private suspend fun autoDownloadEpisode(
        subscription: SubscribedData,
        response: EpisodeResponse,
        dubStatus: DubStatus,
        episodeNumber: Int
    ): Boolean {
        try {
            // Check safeguards before auto-downloading
            if (!hasEnoughStorage()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Not enough storage available")
                showNotification(subscription, response, episodeNumber, dubStatus)
                return true // Handled via notification fallback
            }
            
            if (!isDownloadPathConfigured()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Download path not configured")
                showNotification(subscription, response, episodeNumber, dubStatus)
                return true // Handled via notification fallback
            }
            
            if (!isNetworkAllowedForAutoDownload()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Network type not allowed for auto-download")
                showNotification(subscription, response, episodeNumber, dubStatus)
                return true // Handled via notification fallback
            }
            
            val api = getApiFromNameNull(subscription.apiName) ?: run {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] API not found: ${subscription.apiName}")
                return false // Could not handle, will retry on next check
            }
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_START] Starting auto-download for ${subscription.name} ep $episodeNumber")
            
            // Get episode data from response
            val episodes = when (response) {
                is AnimeLoadResponse -> response.episodes[dubStatus] ?: emptyList()
                is TvSeriesLoadResponse -> response.episodes
                else -> {
                    android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Unsupported response type: ${response::class.simpleName}")
                    emptyList()
                }
            }
            
            // Find the specific episode. Some providers number episodes per-season,
            // so fall back to matching the total episode index across seasons.
            val targetEpisode = episodes.find { it.episode == episodeNumber }
                ?: episodes.firstOrNull { ep ->
                    val season = ep.season
                    val episode = ep.episode
                    season != null && episode != null &&
                        (response as? EpisodeResponse)?.getTotalEpisodeIndex(episode, season) == episodeNumber
                }
                ?: run {
                    android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode $episodeNumber not found in response")
                    return false // Could not handle, will retry on next check
                }
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_EPISODE] Found episode: ${targetEpisode.name}")
            
            // Load extractor links for the episode
            val linkData = withTimeoutOrNull(30_000) {
                val links = mutableListOf<ExtractorLink>()
                api.loadLinks(targetEpisode.data, false, { _ -> }, { links.add(it) })
                links
            }
            
            if (linkData == null || linkData.isEmpty()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] No links found for episode $episodeNumber")
                return false // Could not handle, will retry on next check
            }
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_LINKS] Found ${linkData.size} links")
            
            // Create ResultEpisode for DownloadQueueItem
            val id = ((subscription.id ?: 0) * 100000) + (episodeNumber * 100) + dubStatus.ordinal
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_ID] Generated ID: $id for ${subscription.name} ep $episodeNumber ($dubStatus)")
            
            // Check for duplicate downloads
            if (isAlreadyDownloadedOrQueued(id)) {
                android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode already downloaded or queued, skipping")
                return true // Already handled
            }
            
            // Check storage availability
            if (!hasEnoughStorageForEstimatedSize()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Insufficient storage for download")
                showNotification(subscription, response, episodeNumber, dubStatus)
                return true // Handled via notification fallback
            }
            
            // Prefer the fresh response's name/poster over the subscribe-time copy
            val displayName = (response as? LoadResponse)?.name ?: subscription.name
            val displayPoster = (response as? LoadResponse)?.posterUrl ?: subscription.posterUrl

            val resultEpisode = ResultEpisode(
                headerName = displayName,
                name = targetEpisode.name,
                poster = targetEpisode.posterUrl ?: displayPoster,
                episode = episodeNumber,
                seasonIndex = null,
                season = targetEpisode.season,
                data = targetEpisode.data,
                apiName = api.name,
                id = id,
                index = 0,
                position = 0,
                duration = 0,
                score = targetEpisode.score,
                description = targetEpisode.description,
                isFiller = null,
                tvType = subscription.type ?: com.lagradost.cloudstream3.TvType.TvSeries,
                parentId = subscription.id ?: 0,
                videoWatchState = VideoWatchState.None,
                dubStatus = dubStatus
            )
            
            // Apply download preferences (quality/language) to filter links
            val preferredLinks = DownloadPreferences.selectBestLinks(
                context,
                linkData,
                dubStatus
            ).sortedByDescending { it.quality }
            
            if (preferredLinks.isEmpty()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] No links matched download preferences for episode $episodeNumber")
                showNotification(subscription, response, episodeNumber, dubStatus)
                return true // Handled via notification fallback
            }
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_PREFERRED] Selected ${preferredLinks.size} links from ${linkData.size} total (dubStatus=$dubStatus)")
            
            // Create DownloadQueueItem
            val queueItem = DownloadObjects.DownloadQueueItem(
                episode = resultEpisode,
                isMovie = subscription.type == com.lagradost.cloudstream3.TvType.Movie,
                resultName = displayName,
                resultType = subscription.type ?: com.lagradost.cloudstream3.TvType.TvSeries,
                resultPoster = displayPoster,
                apiName = api.name,
                resultId = subscription.id ?: 0,
                resultUrl = subscription.url,
                links = preferredLinks,
                subs = emptyList(),
                dubStatus = dubStatus
            )
            
            // Add to download queue
            DownloadQueueManager.addToQueue(queueItem.toWrapper())
            
            android.util.Log.i("EpisodeCheck", "[AUTO_DOWNLOAD_QUEUED] Episode $episodeNumber of ${subscription.name} added to queue")
            
            // Show notification that download was queued
            showAutoDownloadNotification(subscription, response, episodeNumber, dubStatus)
            return true // Successfully queued
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[AUTO_DOWNLOAD_ERROR] Failed to auto-download ${subscription.name} ep $episodeNumber", t)
            // Still show notification so user knows there's a new episode
            showNotification(subscription, response, episodeNumber, dubStatus)
            return true // Handled via notification fallback
        }
    }

    private fun showNotification(
        subscription: SubscribedData,
        response: EpisodeResponse,
        episodeNumber: Int,
        dubStatus: DubStatus? = null
    ) {
        try {
            // Prefer the fresh response's name over the subscribe-time copy
            val updateHeader = (response as? LoadResponse)?.name ?: subscription.name
            
            // Build description with dub status if available
            val updateDescription = if (dubStatus != null && dubStatus != DubStatus.None) {
                txt(R.string.subscription_episode_released_dubbed, episodeNumber, updateHeader, dubStatus.name).asString(context)
            } else {
                txt(R.string.subscription_episode_released, episodeNumber, updateHeader).asString(context)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                data = subscription.url.toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }.putExtra(MainActivity.API_NAME_EXTRA_KEY, subscription.apiName)

            val pendingIntent =
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)

            // Load poster bitmap - use synchronous approach since we're not in a coroutine
            val poster = try {
                ((response as? LoadResponse)?.posterUrl ?: subscription.posterUrl)?.let { url ->
                    context.getImageBitmapFromUrl(
                        url,
                        (response as? LoadResponse)?.posterHeaders ?: subscription.posterHeaders
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e("EpisodeCheck", "[NOTIFICATION_POSTER_ERROR] Failed to load poster", e)
                null
            }

            val updateNotification =
                baseNotificationBuilder().setContentTitle(updateHeader)
                    .setContentText(updateDescription)
                    .setContentIntent(pendingIntent)
                    .setLargeIcon(poster)
                    .apply {
                        if (poster != null) {
                            setStyle(
                                NotificationCompat.BigPictureStyle()
                                    .bigPicture(poster)
                                    .bigLargeIcon(null as android.graphics.Bitmap?)
                            )
                        }
                    }
                    .build()

            // Use subscription ID as notification ID (unique per show)
            val notificationId = subscription.id ?: episodeNumber
            notificationManager.notify(notificationId, updateNotification)
            
            android.util.Log.d("EpisodeCheck", "[NOTIFICATION_SHOWN] Notification shown for $updateHeader ep $episodeNumber")
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[NOTIFICATION_ERROR] Failed to show notification", t)
        }
    }

    private fun showAutoDownloadNotification(
        subscription: SubscribedData,
        response: EpisodeResponse,
        episodeNumber: Int,
        dubStatus: DubStatus? = null
    ) {
        try {
            val title = txt(R.string.auto_download_queued_title).asString(context)
            val displayName = (response as? LoadResponse)?.name ?: subscription.name
            
            // Build description
            val description = if (dubStatus != null && dubStatus != DubStatus.None) {
                txt(R.string.auto_download_queued_dubbed, displayName, episodeNumber, dubStatus.name).asString(context)
            } else {
                txt(R.string.auto_download_queued, displayName, episodeNumber).asString(context)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                // Navigate to downloads tab
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }.putExtra("navigate_to", "downloads")

            val pendingIntent =
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)

            val notification =
                baseNotificationBuilder().setContentTitle(title)
                    .setContentText(description)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.netflix_download) // Use download icon
                    .build()

            val notificationId = (subscription.id ?: 0) + 1000000 // Offset to avoid collision with episode notifications
            notificationManager.notify(notificationId, notification)
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_NOTIFICATION_SHOWN] Queued notification for $displayName ep $episodeNumber")
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[AUTO_DOWNLOAD_NOTIFICATION_ERROR] Failed to show notification", t)
        }
    }

    private fun showCompletionNotification(checked: Int, failures: Int, newEpisodes: Int, failureReasons: List<String> = emptyList()) {
        try {
            val title = if (newEpisodes > 0) {
                "New episodes found"
            } else if (failures > 0) {
                "Episode check completed with failures"
            } else {
                "Episode check completed"
            }

            val description = buildString {
                append("$checked checked")
                if (newEpisodes > 0) append(", $newEpisodes with new episodes")
                if (failures > 0) {
                    append(", $failures failed")
                    failureReasons.take(2).forEach { reason ->
                        append("\n- $reason")
                    }
                }
                if (checked == 0) append("No subscriptions found — subscribe to shows to track new episodes")
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent =
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)

            val notification =
                baseNotificationBuilder().setContentTitle(title)
                    .setContentText(description)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.ic_refresh)
                    .setAutoCancel(true)
                    .build()

            notificationManager.notify(EPISODE_CHECK_NOTIFICATION_ID + 1, notification)
            android.util.Log.d("EpisodeCheck", "[COMPLETION_NOTIFICATION] $description")
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[COMPLETION_NOTIFICATION_ERROR]", t)
        }
    }

    private fun hasEnoughStorage(): Boolean {
        return try {
            val downloadPath = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(com.lagradost.cloudstream3.R.string.download_path_key), null)
            
            if (downloadPath.isNullOrBlank()) {
                android.util.Log.d("EpisodeCheck", "[STORAGE_CHECK] Download path is null or blank")
                return false
            }
            
            // SAF content URIs cannot be checked via StatFs; assume enough space
            // The actual download pipeline handles its own storage validation
            if (downloadPath.startsWith("content://")) {
                android.util.Log.d("EpisodeCheck", "[STORAGE_CHECK] SAF path, assuming storage OK")
                return true
            }
            
            val stat = StatFs(downloadPath)
            val availableBytes = stat.availableBytes
            val minBytes = 500L * 1024 * 1024 // 500MB minimum
            val hasStorage = availableBytes > minBytes
            
            android.util.Log.d("EpisodeCheck", "[STORAGE_CHECK] Available: ${availableBytes / (1024 * 1024)}MB, Required: ${minBytes / (1024 * 1024)}MB, Result: $hasStorage")
            hasStorage
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[STORAGE_CHECK_ERROR] Failed to check storage", t)
            false
        }
    }

    private fun isDownloadPathConfigured(): Boolean {
        return try {
            val downloadPath = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(com.lagradost.cloudstream3.R.string.download_path_key), null)
            val isConfigured = !downloadPath.isNullOrBlank()
            
            android.util.Log.d("EpisodeCheck", "[PATH_CHECK] Download path configured: $isConfigured, Path: $downloadPath")
            isConfigured
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[PATH_CHECK_ERROR] Failed to check download path", t)
            false
        }
    }

    private fun isNetworkAllowedForAutoDownload(): Boolean {
        return try {
            val networkPref = DataStoreHelper.autoDownloadNetworkPreference
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val allowed = if (SDK_INT >= 23) {
                val activeNetwork = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
                val isWifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                val isMobile = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                
                when (networkPref) {
                    "wifi_only" -> isWifi
                    "data_only" -> isMobile
                    "both" -> isWifi || isMobile
                    else -> isWifi // Default fallback
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = cm.activeNetworkInfo
                val isWifi = activeNetwork?.type == ConnectivityManager.TYPE_WIFI
                val isMobile = activeNetwork?.type == ConnectivityManager.TYPE_MOBILE
                
                when (networkPref) {
                    "wifi_only" -> isWifi
                    "data_only" -> isMobile
                    "both" -> isWifi || isMobile
                    else -> isWifi
                }
            }
            
            android.util.Log.d("EpisodeCheck", "[NETWORK_CHECK] Pref: $networkPref, Allowed: $allowed")
            allowed
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[NETWORK_CHECK_ERROR] Failed to check network", t)
            false // Default to not allowing on error
        }
    }

    private fun isAlreadyDownloadedOrQueued(id: Int): Boolean {
        // Check if already in queue
        val inQueue = DownloadQueueManager.queue.value.any { it.id == id }
        if (inQueue) {
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode already in queue (ID: $id)")
            return true
        }
        
        // Check if already downloaded
        val context = CloudStreamApp.context ?: return false
        val fileInfo = VideoDownloadManager.getDownloadFileInfo(context, id)
        val isComplete = fileInfo != null &&
                fileInfo.totalBytes > 0 &&
                (fileInfo.fileLength.toFloat() / fileInfo.totalBytes.toFloat()) > 0.98f
        
        if (isComplete) {
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode already downloaded (ID: $id)")
            return true
        }
        
        return false
    }

    private fun hasEnoughStorageForEstimatedSize(estimatedBytes: Long = 500L * 1024 * 1024): Boolean {
        return try {
            val downloadPath = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(com.lagradost.cloudstream3.R.string.download_path_key), null)
            
            if (downloadPath.isNullOrBlank()) return false
            
            // SAF content URIs cannot be checked via StatFs; assume enough space
            if (downloadPath.startsWith("content://")) {
                android.util.Log.d("EpisodeCheck", "[STORAGE_CHECK_ESTIMATE] SAF path, assuming storage OK")
                return true
            }
            
            val stat = StatFs(downloadPath)
            val availableBytes = stat.availableBytes
            // Require estimated size + 500MB buffer
            val hasStorage = availableBytes > (estimatedBytes + 500L * 1024 * 1024)
            
            android.util.Log.d("EpisodeCheck", "[STORAGE_CHECK_ESTIMATE] Available: ${availableBytes / (1024 * 1024)}MB, Required: ${(estimatedBytes + 500L * 1024 * 1024) / (1024 * 1024)}MB, Result: $hasStorage")
            hasStorage
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[STORAGE_CHECK_ESTIMATE_ERROR]", t)
            false
        }
    }
}
