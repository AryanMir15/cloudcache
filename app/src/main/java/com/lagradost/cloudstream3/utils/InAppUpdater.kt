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
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
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
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** MainActivity action that finishes a staged/downloaded update. */
    const val ACTION_INSTALL_UPDATE = "com.lagradost.cloudstream3.INSTALL_UPDATE"
    const val EXTRA_UPDATE_VERSION = "EXTRA_UPDATE_VERSION"

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
        @JsonProperty("updateSize") val updateSize: Long?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null, null)
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
            return Update(false, null, null, null, null, null)
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
            return Update(false, null, null, null, null, null)
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
            found.nodeId,
            foundAsset.size?.toLong()
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
            return Update(false, null, null, null, null, null)
        }

        val found = response.lastOrNull { rel ->
            rel.prerelease == true || rel.tagName == "pre-release"
        }

        val foundAsset = found?.assets?.firstOrNull { it.contentType == "application/vnd.android.package-archive" }

        if (foundAsset == null || foundAsset.browserDownloadUrl.isNullOrBlank()) {
            return Update(false, null, null, null, null, null)
        }

        val tagResponse = try {
            parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse tag JSON: ${e.message}")
            null
        }
        val updateCommitHash = tagResponse?.githubObject?.sha?.trim()?.take(7)
        if (updateCommitHash.isNullOrBlank()) {
            return Update(false, null, null, null, null, null)
        }
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash (installed: ${currentCommitHash()})")

        return Update(
            currentCommitHash() != updateCommitHash,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId,
            foundAsset.size?.toLong()
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

    // ---------------------------------------------------------------------
    // Durable update storage
    //
    // APKs live in filesDir/updates (FileProvider already covers files-path).
    // They are intentionally NOT registered with deleteFileOnExit: a staged
    // update must survive process death so the install can be re-offered.
    // Interrupted downloads go to a `.part` file and only get renamed to the
    // final name once the full size is on disk.
    // ---------------------------------------------------------------------

    private fun updatesDir(context: Context): File = File(context.filesDir, "updates")

    internal fun updateApkFile(context: Context, version: String?): File =
        File(updatesDir(context), "update-${version ?: "unknown"}.apk")

    /** The already-downloaded APK for [version], verified against [expectedSize]. */
    internal fun downloadedUpdateFile(
        context: Context, version: String?, expectedSize: Long?
    ): File? {
        val file = updateApkFile(context, version)
        if (!file.isFile) return null
        return if (expectedSize == null || expectedSize <= 0L) {
            if (file.length() > 0L) file else null
        } else {
            if (file.length() == expectedSize) file else null
        }
    }

    /** Deletes stale partials and every cached APK except [keepVersion]. */
    internal fun cleanupUpdateFiles(context: Context, keepVersion: String?) {
        try {
            val keep = keepVersion?.let { "update-$it.apk" }
            // Keep the pending version's .part — a killed download resumes it
            val keepPart = keepVersion?.let { "update-$it.apk.part" }
            updatesDir(context).listFiles()?.forEach { file ->
                val isPart = file.extension == "part"
                val isUpdateApk =
                    file.extension == "apk" && file.name.startsWith("update-")
                if ((isPart && file.name != keepPart) ||
                    (isUpdateApk && file.name != keep)
                ) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private const val UPDATE_DOWNLOAD_ATTEMPTS = 4

    /**
     * Streams [url] into [temp], resuming an existing partial via HTTP Range.
     * A server that ignores Range (200 instead of 206) makes the file restart
     * from byte 0 so mismatched bytes are never spliced. Connection failures
     * retry — one dropped socket at 99% used to throw away the whole update.
     */
    private suspend fun streamUpdateToFile(
        url: String,
        temp: File,
        expectedSize: Long?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean {
        repeat(UPDATE_DOWNLOAD_ATTEMPTS) { attempt ->
            val existing = if (temp.isFile) temp.length() else 0L
            // A previous attempt may have every byte but died before rename
            if (expectedSize != null && expectedSize > 0 && existing >= expectedSize) {
                return true
            }
            try {
                val response = app.get(
                    url,
                    headers = if (existing > 0) mapOf("Range" to "bytes=$existing-")
                    else emptyMap()
                )
                if (response.code !in 200..299) {
                    Log.w(
                        LOG_TAG,
                        "Update download HTTP ${response.code} (attempt ${attempt + 1})"
                    )
                    return@repeat
                }
                // 206 = range honored → append. Anything else → the server sent
                // the whole body, so truncate and write from scratch.
                val resuming = existing > 0 && response.code == 206 &&
                        run {
                            val start = response.headers["Content-Range"]
                                ?.removePrefix("bytes ")
                                ?.substringBefore("-")
                                ?.toLongOrNull()
                            start == null || start == existing
                        }
                var downloaded = if (resuming) existing else 0L
                // On a 206 the body only carries the remaining bytes — add the
                // part already on disk so `total` means the full file size
                val total = expectedSize?.takeIf { it > 0 }
                    ?: (response.body.contentLength() + if (resuming) existing else 0L)
                temp.sink(append = resuming).buffer().use { sink ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            sink.write(buffer, 0, read)
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (total <= 0 || downloaded == total) return true
                Log.w(
                    LOG_TAG,
                    "Update download short: $downloaded/$total (attempt ${attempt + 1})"
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Update download attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        return false
    }

    /**
     * Returns the APK for this update, downloading it to [updatesDir] first if
     * it is not already fully on disk. Returns null on any failure — callers
     * must not fall back to a partially written file. The .part is kept so a
     * later attempt resumes where this one stopped.
     */
    internal suspend fun ensureDownloaded(
        context: Context,
        url: String,
        version: String?,
        expectedSize: Long?,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File? {
        downloadedUpdateFile(context, version, expectedSize)?.let { return it }
        val target = updateApkFile(context, version)
        val temp = File(target.parentFile, "${target.name}.part")
        return try {
            updateLock.withLock {
                // Another caller may have finished while we waited on the lock
                downloadedUpdateFile(context, version, expectedSize)
                    ?: run {
                        Log.d(LOG_TAG, "Downloading update: $url")
                        target.parentFile?.mkdirs()
                        if (!streamUpdateToFile(url, temp, expectedSize, onProgress)) {
                            return@run null
                        }
                        if (!temp.renameTo(target)) {
                            temp.delete()
                            null
                        } else {
                            // A newer APK finished — drop any older cached builds
                            cleanupUpdateFiles(context, version)
                            target
                        }
                    }
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * Attempts to finish a staged update without touching the network:
     * in-memory delayed session, then the persisted session id (survives
     * process death), then falls back to starting [PackageInstallerService]
     * which resolves the durable APK via [ensureDownloaded].
     */
    fun Activity.tryInstallPendingUpdate(): Boolean {
        if (ApkInstaller.startPendingInstallation(this)) {
            showToast(R.string.update_started, Toast.LENGTH_LONG)
            return true
        }
        val url = getKey<String>(ApkInstaller.PENDING_UPDATE_URL) ?: return false
        val version = getKey<String>(ApkInstaller.PENDING_UPDATE_VERSION)
        val size = getKey<Long>(ApkInstaller.PENDING_UPDATE_SIZE)
        ContextCompat.startForegroundService(
            this, PackageInstallerService.Companion.getIntent(this, url, version, size)
        )
        return true
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

        // Drop stale .part files and APKs for any other (older) version
        cleanupUpdateFiles(this, update.updateVersion)

        // If this exact build is already on disk the button installs instantly
        val alreadyDownloaded =
            downloadedUpdateFile(this, update.updateVersion, update.updateSize) != null

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
                    setPositiveButton(
                        if (alreadyDownloaded) R.string.install else R.string.update
                    ) { _, _ ->
                        // Installing via session or ACTION_VIEW both require the
                        // "install unknown apps" permission. Ask for it up front
                        // instead of failing silently mid-install.
                        if (!packageManager.canRequestPackageInstalls()) {
                            showInstallPermissionDialog(this@runAutoUpdate)
                            return@setPositiveButton
                        }

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

                        // Forcefully start any delayed or persisted installation
                        // first — a leftover staged session installs instantly.
                        if (tryInstallPendingUpdate()) {
                            showUpdateNotification(
                                this@runAutoUpdate,
                                getString(R.string.update_notification_installing),
                                getString(R.string.update_started)
                            )
                            return@setPositiveButton
                        }

                        showToast(
                            if (alreadyDownloaded) R.string.update_started
                            else R.string.download_started,
                            Toast.LENGTH_LONG
                        )

                        // Single path for both installers: the service resolves
                        // the durable file (no network if already downloaded),
                        // then stages a session or fires ACTION_VIEW.
                        ContextCompat.startForegroundService(
                            this@runAutoUpdate,
                            PackageInstallerService.Companion.getIntent(
                                this@runAutoUpdate,
                                update.updateURL,
                                update.updateVersion,
                                update.updateSize
                            )
                        )
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                            // Don't keep installing what the user asked to skip
                            updateApkFile(this@runAutoUpdate, update.updateVersion).delete()
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
