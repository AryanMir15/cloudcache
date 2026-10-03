package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo

/**
 * Single source of truth for "may this downloaded file be handed to ExoPlayer".
 *
 * The original play gate used a >0.98 size ratio, which waved through a file
 * that was 14 bytes short (ep10) and died in media3 with
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003). Both the Play File button
 * ([com.lagradost.cloudstream3.ui.download.DownloadButtonSetup]) and the
 * play-from-cache shortcut ([com.lagradost.cloudstream3.ui.player.RepoLinkGenerator])
 * go through this so a truncated or garbage file can never start playback —
 * it either falls back to the online path or shows an explicit toast.
 */
object DownloadPlaybackGate {
    private const val TAG = "DownloadPlaybackGate"

    enum class PlayableState {
        /** Verified complete and playable — safe to hand to ExoPlayer. */
        Ready,
        /** Status/size says the download never finished. */
        NotReady,
        /** Full size but not media ExoPlayer can parse (error page, bad box). */
        Corrupt,
    }

    /**
     * @param logPrefix distinguishes callers in logcat (`PLAY_FILE id=…`,
     * `CACHE_PLAY id=…`, `AUTO_NEXT_DL id=…`) — the first-bytes hex line is
     * always emitted so blocked files are diagnosable from logcat alone.
     */
    fun check(context: Context, id: Int, logPrefix: String): PlayableState {
        val dlStatus = VideoDownloadManager.downloadStatus[id]
        val fileInfo = VideoDownloadManager.getDownloadFileInfo(context, id)

        val isComplete = when (dlStatus) {
            VideoDownloadManager.DownloadType.IsDone -> true
            null -> when {
                fileInfo == null -> false
                // Size unknown (SAF content URIs report -1) — let the player decide
                fileInfo.totalBytes <= 0 || fileInfo.fileLength < 0 -> true
                // HLS totals are estimates persisted mid-download and can
                // legitimately drift from the file size — skip the check
                isHlsEstimate(id) -> true
                // the old >0.98 ratio waved truncated files through (ep10:
                // 14 bytes short still passed) — require an exact match
                fileInfo.fileLength == fileInfo.totalBytes -> true
                else -> false
            }
            // IsPending/IsDownloading/IsPaused/IsFailed/IsStopped
            else -> false
        }

        if (!isComplete) {
            Log.w(
                TAG,
                "$logPrefix blocked id=$id — incomplete file " +
                        "${fileInfo?.fileLength}/${fileInfo?.totalBytes} status=$dlStatus"
            )
            return PlayableState.NotReady
        }

        // Content check: a full-size file can still start with garbage (error-page
        // bytes at chunk 0 — the downloader uses verify=false) or a box that
        // ExoPlayer's MP4 sniffer rejects (NoDeclaredBrand). Run media3's own
        // box walk and always log the first bytes so blocked files are diagnosable.
        val head = MediaFileSniffer.readHead(context, fileInfo?.path)
        Log.d(TAG, "$logPrefix id=$id first bytes: ${MediaFileSniffer.headToHex(head)}")
        if (!MediaFileSniffer.looksLikePlayableMedia(
                context, fileInfo?.path, fileInfo?.fileLength
            )
        ) {
            Log.w(
                TAG,
                "$logPrefix blocked id=$id — not playable media, " +
                        "first bytes: ${MediaFileSniffer.headToHex(head)}"
            )
            return PlayableState.Corrupt
        }
        return PlayableState.Ready
    }

    /** HLS downloads persist the resume segment index (and an estimated total) in extraInfo. */
    private fun isHlsEstimate(id: Int): Boolean {
        return try {
            getKey<DownloadedFileInfo>(
                VideoDownloadManager.KEY_DOWNLOAD_INFO,
                id.toString()
            )?.extraInfo != null
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }
}
