package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.InAppUpdater
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

class PackageInstallerService : Service() {
    private var installer: ApkInstaller? = null

    private val baseNotification by lazy {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntentCompat.getActivity(this, 0, intent, 0, false)

        NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // If low priority then the notification might not show :(
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(this.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(getString(R.string.update_notification_downloading))
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.rdload)
    }

    override fun onCreate() {
        this.createNotificationChannel(
            UPDATE_CHANNEL_ID,
            UPDATE_CHANNEL_NAME,
            UPDATE_CHANNEL_DESCRIPTION
        )
        if (SDK_INT >= 29)
        startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(UPDATE_NOTIFICATION_ID, baseNotification.build())
    }

    private suspend fun downloadUpdate(url: String, version: String?, size: Long?) {
        try {
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Downloading)

            // One download path for both installers: resolve the durable APK
            // first — if this exact build is already fully on disk this
            // returns instantly with no network traffic.
            val file = InAppUpdater.ensureDownloaded(
                this, url, version, size
            ) { downloaded, total ->
                if (total > 0) {
                    updateNotificationProgress(
                        downloaded / total.toFloat(),
                        ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
            }

            if (file == null) {
                updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
                return
            }

            val useLegacyInstaller = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(getString(R.string.apk_installer_key), 1) != 0

            if (useLegacyInstaller) {
                if (!openApk(file)) {
                    updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
                }
            } else {
                updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Installing)
                installer = ApkInstaller(this)
                installer?.installApk(
                    this,
                    file.inputStream(),
                    file.length(),
                    {},
                    { status -> updateNotificationProgress(0f, status) },
                    version
                )
            }
        } catch (e: Exception) {
            logError(e)
            updateNotificationProgress(0f, ApkInstaller.InstallProgressStatus.Failed)
        }
    }

    /** Legacy install: hand the APK to the system package installer UI. */
    private fun openApk(file: File): Boolean = try {
        val contentUri = FileProvider.getUriForFile(
            this, BuildConfig.APPLICATION_ID + ".provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Service context requires NEW_TASK to launch an activity
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        startActivity(installIntent)
        true
    } catch (e: Exception) {
        logError(e)
        false
    }

    private fun updateNotificationProgress(
        percentage: Float,
        state: ApkInstaller.InstallProgressStatus
    ) {
//        Log.d(LOG_TAG, "Downloading app update progress $percentage | $state")
        val text = when (state) {
            ApkInstaller.InstallProgressStatus.Installing -> R.string.update_notification_installing
            ApkInstaller.InstallProgressStatus.Preparing, ApkInstaller.InstallProgressStatus.Downloading -> R.string.update_notification_downloading
            ApkInstaller.InstallProgressStatus.Failed -> R.string.update_notification_failed
        }

        val newNotification = baseNotification
            .setContentTitle(getString(text))
            .apply {
                if (state == ApkInstaller.InstallProgressStatus.Failed) {
                    setSmallIcon(R.drawable.rderror)
                    setAutoCancel(true)
                } else {
                    setProgress(
                        10000, (10000 * percentage).roundToInt(),
                        state != ApkInstaller.InstallProgressStatus.Downloading
                    )
                }
            }
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Persistent notification on failure
        val id =
            if (state == ApkInstaller.InstallProgressStatus.Failed) UPDATE_NOTIFICATION_ID + 1 else UPDATE_NOTIFICATION_ID
        notificationManager.notify(id, newNotification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val version = intent.getStringExtra(EXTRA_VERSION)
        val size = intent.getLongExtra(EXTRA_SIZE, -1L).takeIf { it > 0 }

        // Persist what is being installed so the Install action can rebuild
        // this service call after process death.
        setKey(ApkInstaller.PENDING_UPDATE_URL, url)
        if (version != null) setKey(ApkInstaller.PENDING_UPDATE_VERSION, version)
        if (size != null) setKey(ApkInstaller.PENDING_UPDATE_SIZE, size)

        ioSafe {
            downloadUpdate(url, version, size)
            // Close the service after the update is done
            // If no sleep then the install prompt may not appear and the notification
            // will disappear instantly
            delay(10_000)
            this@PackageInstallerService.stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        installer?.unregisterInstallActionReceiver()
        installer = null
        this.stopSelf()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onTimeout(reason: Int) {
        stopSelf()
        Log.e("PackageInstallerService", "Service stopped due to timeout: $reason")
    }

    companion object {
        private const val EXTRA_URL = "EXTRA_URL"
        private const val EXTRA_VERSION = "EXTRA_VERSION"
        private const val EXTRA_SIZE = "EXTRA_SIZE"

        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136 // Random unique

        fun getIntent(
            context: Context,
            url: String,
            version: String? = null,
            size: Long? = null,
        ): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_SIZE, size ?: -1L)
        }
    }
}