package com.lagradost.cloudstream3.utils

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_DESCRIPTION
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_ID
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_NAME
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_NOTIFICATION_ID
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import java.io.InputStream

const val INSTALL_ACTION = "ApkInstaller.INSTALL_ACTION"

class ApkInstaller(private val service: PackageInstallerService) {

    companion object {
        /**
         * Used for postponed installations
         **/
        var delayedInstaller: DelayedInstaller? = null
        private var isReceiverRegistered = false
        private const val TAG = "ApkInstaller"

        /**
         * Staged update state, persisted so an install survives process death.
         * [PENDING_SESSION_ID] only exists while a PackageInstaller session is
         * staged; the URL/version/size keys let the service re-resolve the
         * durable APK if the system garbage-collected that session.
         */
        const val PENDING_SESSION_ID = "pending_install_session_id"
        const val PENDING_UPDATE_URL = "pending_update_url"
        const val PENDING_UPDATE_VERSION = "pending_update_version"
        const val PENDING_UPDATE_SIZE = "pending_update_size"

        /**
         * Commits whatever install is already staged. Checks the in-memory
         * delayed installer first, then re-opens the persisted session — this
         * is what makes "Install" still work after the app was killed.
         */
        fun startPendingInstallation(context: Context): Boolean {
            delayedInstaller?.let { return it.startInstallation() }
            val sessionId = context.getKey<Int>(PENDING_SESSION_ID) ?: return false
            return try {
                val session =
                    context.packageManager.packageInstaller.openSession(sessionId)
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(context, PackageInstallerService::class.java)
                        .setAction(INSTALL_ACTION)
                } else {
                    Intent(INSTALL_ACTION)
                }
                val flags = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> PendingIntent.FLAG_MUTABLE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_IMMUTABLE
                    else -> 0
                }
                session.commit(
                    PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
                )
                context.removeKey(PENDING_SESSION_ID)
                true
            } catch (e: Exception) {
                // Session was abandoned/GC'd by the system — caller falls back
                // to the durable APK through the installer service.
                logError(e)
                context.removeKey(PENDING_SESSION_ID)
                false
            }
        }
    }

    inner class DelayedInstaller(
        private val session: PackageInstaller.Session,
        private val intent: IntentSender
    ) {
        fun startInstallation(): Boolean {
            return try {
                session.commit(intent)
                true
            } catch (e: Exception) {
                logError(e)
                false
            }.also {
                delayedInstaller = null
                service.removeKey(PENDING_SESSION_ID)
            }
        }
    }

    private val packageInstaller = service.packageManager.packageInstaller

    enum class InstallProgressStatus {
        Preparing,
        Downloading,
        Installing,
        Failed,
    }

    private val installActionReceiver = object : BroadcastReceiver() {
        @SuppressLint("UnsafeIntentLaunch")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val userAction = intent.getSafeParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(userAction)
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    // Install went through — drop the staged state and the
                    // "Update downloaded" notification.
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(UPDATE_NOTIFICATION_ID)
                    context.removeKey(PENDING_SESSION_ID)
                    context.removeKey(PENDING_UPDATE_URL)
                    context.removeKey(PENDING_UPDATE_VERSION)
                    context.removeKey(PENDING_UPDATE_SIZE)
                }

                else -> {
                    // Session-level failure — the staged session is dead, but
                    // keep the URL/version keys so the durable APK can retry.
                    context.removeKey(PENDING_SESSION_ID)
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    val message = intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE
                    ) ?: "install status $status"
                    Log.e(TAG, "Install session failed: $message")
                    installStatusCallback?.invoke(
                        InstallProgressStatus.Failed, message.take(200)
                    )
                }
            }
        }
    }

    private var installStatusCallback: ((InstallProgressStatus, String?) -> Unit)? = null

    fun installApk(
        context: Context,
        inputStream: InputStream,
        size: Long,
        installProgress: (bytesRead: Int) -> Unit,
        installProgressStatus: (InstallProgressStatus, String?) -> Unit,
        version: String? = null
    ) {
        installProgressStatus.invoke(InstallProgressStatus.Preparing, null)
        installStatusCallback = installProgressStatus
        var activeSession: Int? = null

        try {
            val installParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            installParams.setSize(size)

            activeSession = packageInstaller.createSession(installParams)

            val session = packageInstaller.openSession(activeSession)
            installProgressStatus.invoke(InstallProgressStatus.Downloading, null)

            session.openWrite(context.packageName, 0, size)
                .use { outputStream ->
                    val buffer = ByteArray(4 * 1024)
                    var bytesRead = inputStream.read(buffer)

                    while (bytesRead >= 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesRead = inputStream.read(buffer)
                        installProgress.invoke(bytesRead)
                    }

                    session.fsync(outputStream)
                    inputStream.close()
                }

            // We must create an explicit intent or it will fail on Android 15+
            val installIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { 
                Intent(service, PackageInstallerService::class.java)
                    .setAction(INSTALL_ACTION) 
            } else Intent(INSTALL_ACTION) 

            val installFlags = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> PendingIntent.FLAG_MUTABLE
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_IMMUTABLE
                else -> 0
            }

            val intentSender = PendingIntent.getBroadcast(
                service, activeSession, installIntent, installFlags
            ).intentSender

            // Use delayed installations on android 13 and only if "allow from unknown sources" is enabled
            // if the app lacks installation permission it cannot ask for the permission when it's closed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.packageManager.canRequestPackageInstalls()
            ) {
                // Save for later installation since it's more jarring to have the app exit abruptly
                delayedInstaller = DelayedInstaller(session, intentSender)
                // Persist so the session can be re-opened after process death
                activeSession?.let { service.setKey(PENDING_SESSION_ID, it) }
                main {
                    // Use real toast since it should show even if app is exited
                    Toast.makeText(context, R.string.delayed_update_notice, Toast.LENGTH_LONG)
                        .show()
                }
                // Also post a notification so the pending install is visible if the app exits
                showDelayedInstallNotification(context, version)
            } else {
                installProgressStatus.invoke(InstallProgressStatus.Installing, null)
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            logError(e)

            service.unregisterReceiver(installActionReceiver)
            installProgressStatus.invoke(InstallProgressStatus.Failed, e.message)

            activeSession?.let { sessionId ->
                packageInstaller.abandonSession(sessionId)
            }
        }
    }

    init {
        // Might be dangerous
        registerInstallActionReceiver()
    }

    private fun showDelayedInstallNotification(context: Context, version: String?) {
        try {
            context.createNotificationChannel(
                UPDATE_CHANNEL_ID, UPDATE_CHANNEL_NAME, UPDATE_CHANNEL_DESCRIPTION
            )
            // Tapping Install (or the notification itself) re-enters the app
            // and commits the staged session — works after process death too.
            val installIntent =
                Intent(context, com.lagradost.cloudstream3.MainActivity::class.java).apply {
                    action = InAppUpdater.ACTION_INSTALL_UPDATE
                    putExtra(InAppUpdater.EXTRA_UPDATE_VERSION, version)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val installPendingIntent = PendingIntentCompat.getActivity(
                context, 0, installIntent, PendingIntent.FLAG_UPDATE_CURRENT, false
            )
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
                .setContentTitle(
                    context.getString(R.string.update_downloaded)
                )
                .setContentText(
                    context.getString(R.string.delayed_update_notice)
                )
                .setContentIntent(installPendingIntent)
                .addAction(0, context.getString(R.string.install), installPendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun registerInstallActionReceiver() {
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter().apply {
                addAction(INSTALL_ACTION)
            }
            Log.d(TAG, "Registering install action event receiver")
            context?.registerBroadcastReceiver(installActionReceiver, intentFilter)
            isReceiverRegistered = true
        }
    }

    fun unregisterInstallActionReceiver() {
        if (isReceiverRegistered) {
            Log.d(TAG, "Unregistering install action event receiver")
            try {
                context?.unregisterReceiver(installActionReceiver)
            } catch (e: Exception) {
                logError(e)
            }
            isReceiverRegistered = false
        }
    }
}
