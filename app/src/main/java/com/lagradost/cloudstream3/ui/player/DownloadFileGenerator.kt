package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import com.lagradost.cloudstream3.utils.SubtitleUtils.cleanDisplayName
import com.lagradost.cloudstream3.utils.SubtitleUtils.isMatchingSubtitle
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo

class DownloadFileGenerator(
    episodes: List<ExtractorUri>,
    currentIndex: Int = 0
) : VideoGenerator<ExtractorUri>(episodes, currentIndex) {
    override val hasCache = false
    override val canSkipLoading = false

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        val meta = getCurrent(offset) ?: return false

        android.util.Log.d("DownloadFileGenerator", "generateLinks for episode ${meta.id}, uri=${meta.uri}, relativePath=${meta.relativePath}, basePath=${meta.basePath}")

        var resolvedMeta = meta
        if (resolvedMeta.uri == Uri.EMPTY) {
            // We do this here so that we only load it when
            // we actually need it as it can be more expensive.
            val info = resolvedMeta.id?.let { id ->
                activity?.let { act ->
                    android.util.Log.d("DownloadFileGenerator", "Calling getDownloadFileInfo for episode $id")
                    getDownloadFileInfo(act, id)
                }
            }

            android.util.Log.d("DownloadFileGenerator", "getDownloadFileInfo returned: $info")
            if (info != null) {
                val updatedMeta = meta.copy(uri = info.path)
                resolvedMeta = updatedMeta
                android.util.Log.d("DownloadFileGenerator", "Updated meta.uri to: ${info.path}")
            }
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
                val originalName = cleanName.removePrefix(cleanDisplay).replace(lastNum, "").trim()

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