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
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.RESULT_SUBSCRIBED_STATE_DATA
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import android.os.StatFs
import android.net.ConnectivityManager
import android.preference.PreferenceManager
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

typealias SubscribedData = com.lagradost.cloudstream3.utils.DataStoreHelper.SubscribedData

const val EPISODE_CHECK_CHANNEL_ID = "cloudstream3.episode_check"
const val EPISODE_CHECK_WORK_NAME = "work_episode_check"
const val EPISODE_CHECK_CHANNEL_NAME = "Episode Check"
const val EPISODE_CHECK_CHANNEL_DESCRIPTION = "Notifications for new episodes in ongoing series"
const val EPISODE_CHECK_NOTIFICATION_ID = 938712898 // Random unique

class EpisodeCheckWorkManager(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        const val KEY_RETRY_COUNT = "retry_count"
        const val MAX_API_RETRY_COUNT = 3

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
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
            android.util.Log.d("EpisodeCheck", "[MANUAL_TRIGGER] Manual episode check triggered")
        }

        fun cancelWork(context: Context?) {
            if (context == null) return
            WorkManager.getInstance(context).cancelUniqueWork(EPISODE_CHECK_WORK_NAME)
        }

        fun buildRetryRequest(retryCount: Int): OneTimeWorkRequest {
            val data = Data.Builder()
                .putInt(KEY_RETRY_COUNT, retryCount)
                .build()

            return OneTimeWorkRequest.Builder(EpisodeCheckWorkManager::class.java)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
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

    private val updateNotificationBuilder =
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
        val retryCount = inputData.getInt(KEY_RETRY_COUNT, 0)
        
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

            // Get retry count from input
            val currentRetry = inputData.getInt(KEY_RETRY_COUNT, 0)
            if (currentRetry > 0) {
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_RETRY] Retry attempt $currentRetry/$MAX_API_RETRY_COUNT")
            }
            
            val subscriptions = DataStoreHelper.getAllSubscriptions()
            if (subscriptions.isEmpty()) {
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_SKIP] No subscriptions found, cancelling work")
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
            updateProgress(max, progress, true)
            
            subscriptions.amap { subscription ->
                try {
                    checkSingleSubscription(subscription)
                    updateProgress(max, ++progress, false)
                } catch (t: Throwable) {
                    android.util.Log.e("EpisodeCheck", "[EPISODE_CHECK_ERROR] Failed checking: ${subscription.name}", t)
                    // Continue with other subscriptions, don't fail entire work
                }
            }
            
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_COMPLETE] Episode check work completed successfully")
            return Result.success()
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[EPISODE_CHECK_FATAL] Episode check failed", t)
            logError(t)
            
            // Retry via WorkManager if under max retries
            return if (retryCount < MAX_API_RETRY_COUNT) {
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_RETRY] Scheduling retry ${retryCount + 1}/$MAX_API_RETRY_COUNT")
                Result.retry()
            } else {
                android.util.Log.w("EpisodeCheck", "[EPISODE_CHECK_RETRY] Max retries exceeded, giving up")
                Result.success() // Don't crash, just give up
            }
        }
    }

    private suspend fun checkSingleSubscription(subscription: SubscribedData) {
        val id = subscription.id ?: return
        val api = getApiFromNameNull(subscription.apiName) ?: run {
            android.util.Log.w("EpisodeCheck", "[EPISODE_CHECK_SKIP] API not found: ${subscription.apiName}")
            return
        }
        
        android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_START] Checking: ${subscription.name}")
        
        // Fetch fresh data from API with timeout
        val response = withTimeoutOrNull(60_000) {
            api.load(subscription.url) as? EpisodeResponse
        }
        
        if (response == null) {
            android.util.Log.w("EpisodeCheck", "[EPISODE_CHECK_SKIP] No response for: ${subscription.name}")
            return
        }
        
        // Get latest episode counts per dub status
        val latestEpisodes = response.getLatestEpisodes()
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
        
        // Update cache with fresh episode count (for offline mode support)
        updateCachedEpisodeCount(id, latestEpisodes)
        
        // Update subscription tracking
        DataStoreHelper.updateSubscribedData(id, subscription, response)
        
        // Notify or auto-download for each dub status with new episodes
        newEpisodesByStatus.forEach { (status, latestEpisode) ->
            handleNewEpisodes(subscription, response, status, latestEpisode)
        }
    }

    private fun updateCachedEpisodeCount(id: Int, latestEpisodes: Map<DubStatus, Int?>) {
        // Calculate total episode count (sum across all dub statuses, or max)
        val totalEpisodes = latestEpisodes.values.filterNotNull().maxOrNull() ?: return
        
        // Update the cached header
        val cacheKey = CloudStreamApp.getKeys(DOWNLOAD_HEADER_CACHE)
            ?.find { key ->
                val header = CloudStreamApp.getKey<DownloadHeaderCached>(key)
                header?.id == id
            }
        
        cacheKey?.let { key ->
            val existing = CloudStreamApp.getKey<DownloadHeaderCached>(key)
            existing?.let { header ->
                val updated = header.copy(
                    episodeCount = totalEpisodes,
                    cacheTime = System.currentTimeMillis()
                )
                CloudStreamApp.setKey(DOWNLOAD_HEADER_CACHE, key, updated)
                android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_CACHE] Updated cache for ${header.name}: $totalEpisodes episodes")
            }
        }
    }

    private suspend fun handleNewEpisodes(
        subscription: SubscribedData,
        response: EpisodeResponse,
        dubStatus: DubStatus,
        latestEpisode: Int
    ) {
        val shouldAutoDownload = DataStoreHelper.autoDownloadSubscribedEpisodes
        
        if (shouldAutoDownload) {
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_TRIGGER] Auto-downloading ${subscription.name} ep $latestEpisode ($dubStatus)")
            autoDownloadEpisode(subscription, response, dubStatus, latestEpisode)
        } else {
            android.util.Log.d("EpisodeCheck", "[EPISODE_CHECK_NOTIFY] Showing notification for ${subscription.name} ep $latestEpisode")
            showNotification(subscription, latestEpisode, dubStatus)  // Pass dubStatus
        }
    }

    private suspend fun autoDownloadEpisode(
        subscription: SubscribedData,
        response: EpisodeResponse,
        dubStatus: DubStatus,
        episodeNumber: Int
    ) {
        try {
            // Check safeguards before auto-downloading
            if (!hasEnoughStorage()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Not enough storage available")
                showNotification(subscription, episodeNumber)
                return
            }
            
            if (!isDownloadPathConfigured()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Download path not configured")
                showNotification(subscription, episodeNumber)
                return
            }
            
            if (!isNetworkAllowedForAutoDownload()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Network type not allowed for auto-download")
                showNotification(subscription, episodeNumber, dubStatus)
                return
            }
            
            val api = getApiFromNameNull(subscription.apiName) ?: run {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] API not found: ${subscription.apiName}")
                return
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
            
            // Find the specific episode
            val targetEpisode = episodes.find { it.episode == episodeNumber } ?: run {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode $episodeNumber not found in response")
                return
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
                return
            }
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_LINKS] Found ${linkData.size} links")
            
            // Create ResultEpisode for DownloadQueueItem
            val id = ((subscription.id ?: 0) * 100000) + (episodeNumber * 100) + dubStatus.ordinal
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_ID] Generated ID: $id for ${subscription.name} ep $episodeNumber ($dubStatus)")
            
            // Check for duplicate downloads
            if (isAlreadyDownloadedOrQueued(id)) {
                android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Episode already downloaded or queued, skipping")
                return
            }
            
            // Check storage availability
            if (!hasEnoughStorageForEstimatedSize()) {
                android.util.Log.w("EpisodeCheck", "[AUTO_DOWNLOAD_SKIP] Insufficient storage for download")
                showNotification(subscription, episodeNumber, dubStatus)
                return
            }
            
            val resultEpisode = ResultEpisode(
                headerName = subscription.name,
                name = targetEpisode.name,
                poster = targetEpisode.posterUrl ?: subscription.posterUrl,
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
                videoWatchState = VideoWatchState.None
            )
            
            // Create DownloadQueueItem
            val queueItem = DownloadObjects.DownloadQueueItem(
                episode = resultEpisode,
                isMovie = false,
                resultName = subscription.name,
                resultType = subscription.type ?: com.lagradost.cloudstream3.TvType.TvSeries,
                resultPoster = subscription.posterUrl,
                apiName = api.name,
                resultId = subscription.id ?: 0,
                resultUrl = subscription.url,
                links = linkData,
                subs = emptyList()
            )
            
            // Add to download queue
            DownloadQueueManager.addToQueue(queueItem.toWrapper())
            
            android.util.Log.i("EpisodeCheck", "[AUTO_DOWNLOAD_QUEUED] Episode $episodeNumber of ${subscription.name} added to queue")
            
            // Show notification that download was queued
            showAutoDownloadNotification(subscription, episodeNumber)
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[AUTO_DOWNLOAD_ERROR] Failed to auto-download ${subscription.name} ep $episodeNumber", t)
            // Still show notification so user knows there's a new episode
            showNotification(subscription, episodeNumber)
        }
    }

    private fun showNotification(subscription: SubscribedData, episodeNumber: Int, dubStatus: DubStatus? = null) {
        try {
            val updateHeader = subscription.name
            
            // Build description with dub status if available
            val updateDescription = if (dubStatus != null && dubStatus != DubStatus.None) {
                txt(R.string.subscription_episode_released_dubbed, episodeNumber, subscription.name, dubStatus.name).asString(context)
            } else {
                txt(R.string.subscription_episode_released, episodeNumber, subscription.name).asString(context)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                data = subscription.url.toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }.putExtra(MainActivity.API_NAME_EXTRA_KEY, subscription.apiName)

            val pendingIntent =
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)

            // Load poster bitmap - use synchronous approach since we're not in a coroutine
            val poster = try {
                subscription.posterUrl?.let { url ->
                    context.getImageBitmapFromUrl(
                        url,
                        subscription.posterHeaders
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e("EpisodeCheck", "[NOTIFICATION_POSTER_ERROR] Failed to load poster", e)
                null
            }

            val updateNotification =
                updateNotificationBuilder.setContentTitle(updateHeader)
                    .setContentText(updateDescription)
                    .setContentIntent(pendingIntent)
                    .setLargeIcon(poster)
                    .build()

            // Use subscription ID as notification ID (unique per show)
            val notificationId = subscription.id ?: episodeNumber
            notificationManager.notify(notificationId, updateNotification)
            
            android.util.Log.d("EpisodeCheck", "[NOTIFICATION_SHOWN] Notification shown for ${subscription.name} ep $episodeNumber")
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[NOTIFICATION_ERROR] Failed to show notification", t)
        }
    }

    private fun showAutoDownloadNotification(subscription: SubscribedData, episodeNumber: Int, dubStatus: DubStatus? = null) {
        try {
            val title = txt(R.string.auto_download_queued_title).asString(context)
            
            // Build description
            val description = if (dubStatus != null && dubStatus != DubStatus.None) {
                txt(R.string.auto_download_queued_dubbed, subscription.name, episodeNumber, dubStatus.name).asString(context)
            } else {
                txt(R.string.auto_download_queued, subscription.name, episodeNumber).asString(context)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                // Navigate to downloads tab
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }.putExtra("navigate_to", "downloads")

            val pendingIntent =
                PendingIntentCompat.getActivity(context, 0, intent, 0, false)

            val notification =
                updateNotificationBuilder.setContentTitle(title)
                    .setContentText(description)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.netflix_download) // Use download icon
                    .build()

            val notificationId = (subscription.id ?: 0) + 1000000 // Offset to avoid collision with episode notifications
            notificationManager.notify(notificationId, notification)
            
            android.util.Log.d("EpisodeCheck", "[AUTO_DOWNLOAD_NOTIFICATION_SHOWN] Queued notification for ${subscription.name} ep $episodeNumber")
            
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "[AUTO_DOWNLOAD_NOTIFICATION_ERROR] Failed to show notification", t)
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
            val activeNetwork = cm.activeNetworkInfo
            val isWifi = activeNetwork?.type == ConnectivityManager.TYPE_WIFI
            val isMobile = activeNetwork?.type == ConnectivityManager.TYPE_MOBILE
            
            val allowed = when (networkPref) {
                "wifi_only" -> isWifi
                "data_only" -> isMobile
                "both" -> isWifi || isMobile
                else -> isWifi // Default fallback
            }
            
            android.util.Log.d("EpisodeCheck", "[NETWORK_CHECK] Pref: $networkPref, WiFi: $isWifi, Mobile: $isMobile, Allowed: $allowed")
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
