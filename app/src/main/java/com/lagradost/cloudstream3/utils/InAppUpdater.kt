package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_DESCRIPTION
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_ID
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_CHANNEL_NAME
import com.lagradost.cloudstream3.services.PackageInstallerService.Companion.UPDATE_NOTIFICATION_ID
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private const val GITHUB_USER_NAME = "AryanMir15"
    private const val GITHUB_REPO = "cloudcache"

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudcache.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    private data class GithubAsset(
        @JsonProperty("name") val name: String?,
        @JsonProperty("size") val size: Int?,
        @JsonProperty("browser_download_url") val browserDownloadUrl: String?,
        @JsonProperty("content_type") val contentType: String?,
    )

    private data class GithubRelease(
        @JsonProperty("tag_name") val tagName: String?,
        @JsonProperty("body") val body: String?,
        @JsonProperty("assets") val assets: List<GithubAsset>?,
        @JsonProperty("target_commitish") val targetCommitish: String?,
        @JsonProperty("prerelease") val prerelease: Boolean?,
        @JsonProperty("node_id") val nodeId: String?,
    )

    private data class GithubObject(
        @JsonProperty("sha") val sha: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("url") val url: String?,
    )

    private data class GithubTag(
        @JsonProperty("object") val githubObject: GithubObject?,
    )

    private data class Update(
        @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") val updateNodeId: String?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val responseText = app.get(url, headers = headers).text
        val response = try {
            parseJson<List<GithubRelease>>(responseText)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse releases JSON: ${e.message}")
            null
        }
        if (response.isNullOrEmpty()) {
            return Update(false, null, null, null, null)
        }

        val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*\.apk)""")
        val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")
        val foundList = response.filter { rel ->
            rel.prerelease == true
        }.sortedWith(compareBy { release ->
            release.assets?.firstOrNull { it.contentType == "application/vnd.android.package-archive" }?.name?.let { it1 ->
                versionRegex.find(
                    it1
                )?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }
        }).toList()

        val found = foundList.lastOrNull()
        val foundAsset = found?.assets?.firstOrNull { it.contentType == "application/vnd.android.package-archive" }
        val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }

        if (foundVersion == null || foundAsset?.browserDownloadUrl.isNullOrBlank()) {
            return Update(false, null, null, null, null)
        }

        val currentVersion = packageName?.let {
            packageManager.getPackageInfo(it, 0)
        }

        val shouldUpdate = currentVersion?.versionName?.let { versionName ->
            versionRegexLocal.find(versionName)?.groupValues?.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            }
        }?.compareTo(
            foundVersion.groupValues.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            })!! < 0

        return Update(
            shouldUpdate,
            foundAsset!!.browserDownloadUrl,
            foundVersion.groupValues[2],
            found.body,
            found.nodeId
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val responseText = app.get(releaseUrl, headers = headers).text
        val response = try {
            parseJson<List<GithubRelease>>(responseText)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse releases JSON: ${e.message}")
            null
        }
        if (response.isNullOrEmpty()) {
            return Update(false, null, null, null, null)
        }

        val found = response.lastOrNull { rel ->
            rel.prerelease == true || rel.tagName == "pre-release"
        }

        val foundAsset = found?.assets?.firstOrNull { it.contentType == "application/vnd.android.package-archive" }

        if (foundAsset == null || foundAsset.browserDownloadUrl.isNullOrBlank()) {
            return Update(false, null, null, null, null)
        }

        val tagResponse = try {
            parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse tag JSON: ${e.message}")
            null
        }
        val updateCommitHash = tagResponse?.githubObject?.sha?.trim()?.take(7)
        if (updateCommitHash.isNullOrBlank()) {
            return Update(false, null, null, null, null)
        }
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash (installed: ${currentCommitHash()})")

        return Update(
            currentCommitHash() != updateCommitHash,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId
        )
    }

    private val updateLock = Mutex()

    /**
     * Posts a notification on the app-updates channel. Toasts vanish when the
     * app goes to the background, but the update flow keeps running there, so
     * this makes update progress visible regardless.
     */
    private fun showUpdateNotification(context: Context, title: String, text: String) {
        try {
            context.createNotificationChannel(
                UPDATE_CHANNEL_ID, UPDATE_CHANNEL_NAME, UPDATE_CHANNEL_DESCRIPTION
            )
            val intent = Intent(context, com.lagradost.cloudstream3.MainActivity::class.java)
            val pendingIntent = PendingIntentCompat.getActivity(context, 0, intent, 0, false)
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logError(e)
        }
    }

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()
                // Only report success if the install prompt could actually be opened
                return openApk(this, Uri.fromFile(downloadedFile))
            }
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri): Boolean = try {
        val path = uri.path ?: return false
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
        true
    } catch (e: Exception) {
        logError(e)
        false
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations.
                        // If a leftover session from a previous update exists, commit it
                        // but tell the user what is happening instead of silently swallowing
                        // the new download.
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) {
                            showToast(R.string.update_started, Toast.LENGTH_LONG)
                            showUpdateNotification(
                                this@runAutoUpdate,
                                getString(R.string.update_notification_installing),
                                getString(R.string.update_started)
                            )
                            return@setPositiveButton
                        }

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        // Installing via session or ACTION_VIEW both require the
                        // "install unknown apps" permission. Ask for it up front
                        // instead of failing silently mid-install.
                        if (!packageManager.canRequestPackageInstalls()) {
                            showInstallPermissionDialog(this@runAutoUpdate)
                            return@setPositiveButton
                        }

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun Activity.showInstallPermissionDialog(context: Context) {
        try {
            val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            builder.setTitle(R.string.install_unknown_sources_title)
            builder.setMessage(R.string.install_unknown_sources_message)
            builder.setPositiveButton(R.string.install_unknown_sources_open) { _, _ ->
                // Directly opens this app's "install unknown apps" screen (API 26+)
                try {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (e: Exception) {
                    logError(e)
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                        )
                    } catch (e2: Exception) {
                        logError(e2)
                    }
                }
            }
            builder.setNegativeButton(R.string.cancel) { _, _ -> }
            builder.show()
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
