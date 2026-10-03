package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import com.lagradost.cloudstream3.utils.SubtitleUtils.cleanDisplayName
import com.lagradost.cloudstream3.utils.SubtitleUtils.isMatchingSubtitle
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo

/**
 * Shared "play this episode from the file on disk" emission used by the
 * play-from-cache shortcut in [RepoLinkGenerator] (mirrors what
 * [DownloadFileGenerator] does for offline-list playback: resolve the uri,
 * emit the local video, scan the episode folder for matching subtitles).
 */
object OfflinePlayback {
    private const val TAG = "OfflinePlayback"

    /**
     * Resolves [meta]'s uri when it is [Uri.EMPTY] (via getDownloadFileInfo),
     * emits the local video through [callback] and scans the episode folder for
     * downloaded subtitles.
     *
     * Returns false when the file cannot be resolved — the caller must then
     * fall back to the online path instead of emitting a broken uri.
     */
    fun emitLocalEpisode(
        meta: ExtractorUri,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
    ): Boolean {
        var resolvedMeta = meta
        if (resolvedMeta.uri == Uri.EMPTY) {
            // resolved only now so nothing touches disk when the episode is
            // never actually opened
            val info = resolvedMeta.id?.let { id ->
                activity?.let { act -> getDownloadFileInfo(act, id) }
            }
            if (info == null) {
                Log.e(TAG, "emitLocalEpisode: no resolvable file for id=${meta.id}")
                return false
            }
            resolvedMeta = meta.copy(uri = info.path)
        }
        callback(null to resolvedMeta)

        val ctx = context ?: return true
        val relative = resolvedMeta.relativePath ?: return true
        val display = resolvedMeta.displayName ?: return true

        val cleanDisplay = cleanDisplayName(display)
        getFolder(ctx, relative, resolvedMeta.basePath)?.forEach { (name, uri) ->
            if (isMatchingSubtitle(name, display, cleanDisplay)) {
                val cleanName = cleanDisplayName(name)
                val lastNum = Regex(" ([0-9]+)$")
                val nameSuffix = lastNum.find(cleanName)?.groupValues?.get(1) ?: ""
                val originalName =
                    cleanName.removePrefix(cleanDisplay).replace(lastNum, "").trim()

                subtitleCallback(
                    SubtitleData(
                        originalName.ifBlank { ctx.getString(R.string.default_subtitles) },
                        nameSuffix,
                        uri.toString(),
                        SubtitleOrigin.DOWNLOADED_FILE,
                        name.toSubtitleMimeType(),
                        emptyMap(),
                        fromLanguageToTagIETF(originalName, true)
                    )
                )
            }
        }
        return true
    }
}
