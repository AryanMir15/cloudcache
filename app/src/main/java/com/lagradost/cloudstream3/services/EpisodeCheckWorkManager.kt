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
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.RESULT_SUBSCRIBED_STATE_DATA
import com.lagradost.cloudstream3.DubStatus
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

const val EPISODE_CHECK_CHANNEL_ID = "cloudstream3.episode_check"
const val EPISODE_CHECK_WORK_NAME = "work_episode_check"
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
        try {
            android.util.Log.d("EpisodeCheck", "Episode check work started")
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

            // Get all cached headers from DOWNLOAD_HEADER_CACHE
            val headerKeys = CloudStreamApp.getKeys(DOWNLOAD_HEADER_CACHE)
            val headers = headerKeys?.mapNotNull { key ->
                CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(key)
            } ?: emptyList()

            if (headers.isEmpty()) {
                android.util.Log.d("EpisodeCheck", "No cached headers found, cancelling work")
                WorkManager.getInstance(context).cancelWorkById(this.id)
                return Result.success()
            }

            android.util.Log.d("EpisodeCheck", "Found ${headers.size} cached headers to check")

            // Get all subscription data
            val subscriptions = DataStoreHelper.getAllSubscriptions()
            val subscriptionsById = subscriptions.associateBy { it.id }

            // Filter for subscribed shows
            val subscribedHeaders = headers.filter { it.id in subscriptionsById }

            if (subscribedHeaders.isEmpty()) {
                android.util.Log.d("EpisodeCheck", "No subscribed shows in cache, cancelling work")
                WorkManager.getInstance(context).cancelWorkById(this.id)
                return Result.success()
            }

            android.util.Log.d("EpisodeCheck", "Found ${subscribedHeaders.size} subscribed shows to check")

            val max = subscribedHeaders.size
            var progress = 0

            updateProgress(max, progress, true)

            // Get all episodes for episode title lookup
            val episodeKeys = CloudStreamApp.getKeys(DOWNLOAD_EPISODE_CACHE)
            val episodes = episodeKeys?.mapNotNull { key ->
                CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(key)
            } ?: emptyList()
            val episodesByParentId = episodes.groupBy { it.parentId }

            subscribedHeaders.amap { header ->
                try {
                    val id = header.id ?: return@amap null
                    val subscription = subscriptionsById[id] ?: return@amap null

                    android.util.Log.d("EpisodeCheck", "Checking episodes for: ${header.name}")

                    // Get cached episode count
                    val cachedEpisodeCount = header.episodeCount ?: 0

                    // Get last seen episode count from subscription
                    val lastSeenCount = subscription.lastSeenEpisodeCount[DubStatus.Subbed] ?: 0

                    android.util.Log.d("EpisodeCheck", "Cached episodes: $cachedEpisodeCount, Last seen: $lastSeenCount")

                    // Check if there are new episodes
                    if (cachedEpisodeCount > lastSeenCount) {
                        android.util.Log.d("EpisodeCheck", "New episodes found for: ${header.name}")

                        // Get latest episode for title
                        val headerEpisodes = episodesByParentId[id] ?: emptyList()
                        val latestEpisode = headerEpisodes.maxByOrNull { it.episode }

                        val episodeTitle = latestEpisode?.name?.takeIf { it.isNotBlank() }
                        val episodeNumber = latestEpisode?.episode ?: cachedEpisodeCount

                        // Create notification
                        val updateHeader = header.name
                        val updateDescription = if (episodeTitle != null) {
                            txt(R.string.subscription_episode_released, episodeNumber, header.name).asString(context) + 
                            " - $episodeTitle"
                        } else {
                            txt(R.string.subscription_episode_released, episodeNumber, header.name).asString(context)
                        }

                        val intent = Intent(context, MainActivity::class.java).apply {
                            data = header.url.toUri()
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }.putExtra(MainActivity.API_NAME_EXTRA_KEY, header.apiName)

                        val pendingIntent =
                            PendingIntentCompat.getActivity(context, 0, intent, 0, false)

                        val poster = ioWork {
                            header.poster?.let { url ->
                                context.getImageBitmapFromUrl(
                                    url,
                                    emptyMap()
                                )
                            }
                        }

                        val updateNotification =
                            updateNotificationBuilder.setContentTitle(updateHeader)
                                .setContentText(updateDescription)
                                .setContentIntent(pendingIntent)
                                .setLargeIcon(poster)
                                .build()

                        notificationManager.notify(id, updateNotification)

                        // Update subscription data with new episode count
                        val updatedData = subscription.copy(
                            latestUpdatedTime = System.currentTimeMillis(),
                            lastSeenEpisodeCount = mapOf(DubStatus.Subbed to cachedEpisodeCount)
                        )
                        CloudStreamApp.setKey(
                            "${DataStoreHelper.currentAccount}/$RESULT_SUBSCRIBED_STATE_DATA",
                            id.toString(),
                            updatedData
                        )
                    }

                    updateProgress(max, ++progress, false)
                } catch (t: Throwable) {
                    android.util.Log.e("EpisodeCheck", "Error checking episodes for ${header.name}", t)
                    logError(t)
                }
            }

            android.util.Log.d("EpisodeCheck", "Episode check work completed")
            return Result.success()
        } catch (t: Throwable) {
            android.util.Log.e("EpisodeCheck", "Episode check work failed", t)
            logError(t)
            return Result.success()
        }
    }
}
