package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadPlaybackGate
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlin.math.max
import kotlin.math.min

data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    var lastCachedTimestamp: Long = unixTime,
    /** If it has fully loaded */
    var saturated: Boolean,
)

class RepoLinkGenerator(
    episodes: List<ResultEpisode>,
    currentIndex: Int = 0,
    val page: LoadResponse? = null,
    val resultUrl: String? = null,
) : VideoGenerator<ResultEpisode>(episodes, currentIndex) {
    companion object {
        const val TAG = "RepoLink"
        val cache: HashMap<Pair<String, Int>, Cache> =
            hashMapOf()
    }

    override val hasCache = true
    override val canSkipLoading = true

    // this is a simple array that is used to instantly load links if they are already loaded
    //var linkCache = Array<Set<ExtractorLink>>(size = episodes.size, init = { setOf() })
    //var subsCache = Array<Set<SubtitleData>>(size = episodes.size, init = { setOf() })

    @Throws
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val current = getCurrent(offset) ?: return false

        // Play from cache: if this episode's file already passed the download
        // gate, skip the provider/extractor crawl entirely — clicking next on a
        // cached episode starts instantly from disk (and works fully offline).
        // Casting is excluded: Chromecast cannot read local files.
        if (!isCasting && tryEmitCachedEpisode(current, callback, subtitleCallback)) {
            Log.i(TAG, "CACHE_PLAY id=${current.id} ep=${current.episode} served from local file")
            return true
        }

        val currentCache = synchronized(cache) {
            cache[current.apiName to current.id] ?: Cache(
                mutableSetOf(),
                mutableSetOf(),
                unixTime,
                false
            ).also {
                cache[current.apiName to current.id] = it
            }
        }

        // these act as a general filter to prevent duplication of links or names
        val currentLinksUrls = mutableSetOf<String>()       // makes all urls unique
        val currentSubsUrls = mutableSetOf<String>()    // makes all subs urls unique
        val lastCountedSuffix = mutableMapOf<String, UInt>()

        synchronized(currentCache) {
            val outdatedCache =
                unixTime - currentCache.lastCachedTimestamp > 60 * 20 // 20 minutes

            if (outdatedCache || clearCache) {
                currentCache.linkCache.clear()
                currentCache.subtitleCache.clear()
                currentCache.saturated = false
            } else if (currentCache.linkCache.isNotEmpty()) {
                Log.d(TAG, "Resumed previous loading from ${unixTime - currentCache.lastCachedTimestamp}s ago")
            }

            // call all callbacks
            currentCache.linkCache.forEach { link ->
                currentLinksUrls.add(link.url)
                if (sourceTypes.contains(link.type)) {
                    callback(link to null)
                }
            }

            currentCache.subtitleCache.forEach { sub ->
                currentSubsUrls.add(sub.url)
                val suffixCount = lastCountedSuffix.getOrDefault(sub.originalName, 0u) + 1u
                lastCountedSuffix[sub.originalName] = suffixCount
                subtitleCallback(sub)
            }

            // this stops all execution if links are cached
            // no extra get requests
            if (currentCache.saturated) {
                return true
            }
        }

        val result = APIRepository(
            getApiFromNameNull(current.apiName) ?: throw Exception("This provider does not exist")
        ).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = { file ->
                Log.d(TAG, "Loaded SubtitleFile: $file")
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || currentSubsUrls.contains(correctFile.url)) {
                    return@loadLinks
                }
                currentSubsUrls.add(correctFile.url)

                // this part makes sure that all names are unique for UX

                val nameDecoded = correctFile.originalName.html().toString().trim() // `%3Ch1%3Esub%20name…` → `<h1>sub name…` → `sub name…`

                val suffixCount = lastCountedSuffix.getOrDefault(nameDecoded, 0u) +1u
                lastCountedSuffix[nameDecoded] = suffixCount

                val updatedFile =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                synchronized(currentCache) {
                    if (currentCache.subtitleCache.add(updatedFile)) {
                        subtitleCallback(updatedFile)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            },
            callback = { link ->
                Log.d(TAG, "Loaded ExtractorLink: $link")
                if (link.url.isBlank() || currentLinksUrls.contains(link.url)) {
                    return@loadLinks
                }
                currentLinksUrls.add(link.url)

                synchronized(currentCache) {
                    if (currentCache.linkCache.add(link)) {
                        if (sourceTypes.contains(link.type)) {
                            callback(Pair(link, null))
                        }

                        currentCache.linkCache.add(link)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            }
        )

        synchronized(currentCache) {
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result
    }

    /**
     * Returns true when [episode] was fully emitted as a local file. Any doubt
     * (no file, incomplete, corrupt, unreadable) returns false so playback
     * falls back to the normal online path — the gate can never trap the
     * player on a broken local file.
     */
    private fun tryEmitCachedEpisode(
        episode: ResultEpisode,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
    ): Boolean {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return false
        return try {
            if (DownloadPlaybackGate.check(
                    ctx, episode.id, "CACHE_PLAY"
                ) != DownloadPlaybackGate.PlayableState.Ready
            ) {
                false
            } else {
                val stored = getKey<DownloadObjects.DownloadedFileInfo>(
                    VideoDownloadManager.KEY_DOWNLOAD_INFO,
                    episode.id.toString()
                ) ?: return false
                OfflinePlayback.emitLocalEpisode(
                    ExtractorUri(
                        uri = Uri.EMPTY, // resolved inside emitLocalEpisode
                        name = episode.name ?: "Episode ${episode.episode}",
                        basePath = stored.basePath,
                        relativePath = stored.relativePath,
                        displayName = stored.displayName,
                        id = episode.id,
                        parentId = episode.parentId,
                        episode = episode.episode,
                        season = episode.season,
                        headerName = episode.headerName,
                        tvType = episode.tvType,
                    ),
                    callback,
                    subtitleCallback,
                )
            }
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }
}