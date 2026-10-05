package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.LinkedSourceManager
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

        /** Don't re-tag a link whose name already declares its variant. */
        private val DUB_SUB_WORD = Regex("""\b(dub|sub)\b""", RegexOption.IGNORE_CASE)
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

        // Shared merge handlers — used for BOTH the primary provider and a
        // linked secondary source so URL dedup and unique-name rules apply
        // uniformly across everything that streams into this episode's cache.
        val onSubtitle: (SubtitleFile) -> Unit = sub@ { file ->
            Log.d(TAG, "Loaded SubtitleFile: $file")
            val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
            if (correctFile.url.isBlank() || currentSubsUrls.contains(correctFile.url)) {
                return@sub
            }
            currentSubsUrls.add(correctFile.url)

            // this part makes sure that all names are unique for UX

            val nameDecoded = correctFile.originalName.html().toString().trim() // decoded html → plain name

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
        }

        val onLink: (ExtractorLink) -> Unit = link@ { link ->
            Log.d(TAG, "Loaded ExtractorLink: $link")
            if (link.url.isBlank() || currentLinksUrls.contains(link.url)) {
                return@link
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

        val result = APIRepository(
            getApiFromNameNull(current.apiName) ?: throw Exception("This provider does not exist")
        ).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = onSubtitle,
            callback = onLink,
        )

        // Also pull links from the entry linked on the result page (playback
        // only). Runs AFTER the primary load so its links arrive late — the
        // player tolerates that — and any failure falls back silently to the
        // primary-only list.
        val linkedResult = try {
            mergeLinkedSource(current, isCasting, onSubtitle, onLink)
        } catch (t: Throwable) {
            Log.e(TAG, "[LINKED_SRC] merge failed", t)
            false
        }

        synchronized(currentCache) {
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result || linkedResult
    }

    /**
     * Resolves the linked secondary entry (LinkedSourceManager), aligns its
     * episode with [current] by (season, episode number) across ALL dub/sub
     * lists, and streams its links/subtitles through the shared handlers.
     *
     * Returns true when at least one secondary loadLinks call succeeded.
     * Never touches metadata, episode ids, or the cache key — everything lands
     * in the primary episode's cache under the primary identity.
     */
    private suspend fun mergeLinkedSource(
        current: ResultEpisode,
        isCasting: Boolean,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = this.page ?: return false
        val linked = LinkedSourceManager.get(page.apiName, page.url) ?: return false
        if (linked.secondaryApiName.equals(page.apiName, ignoreCase = true) &&
            linked.secondaryUrl == page.url
        ) {
            return false
        }
        Log.i(
            TAG,
            "[LINKED_SRC] ${page.apiName} -> ${linked.secondaryApiName}" +
                    " (${linked.secondaryName})"
        )
        val api = getApiFromNameNull(linked.secondaryApiName) ?: run {
            Log.w(TAG, "[LINKED_SRC] provider not installed: ${linked.secondaryApiName}")
            return false
        }
        val repo = APIRepository(api)
        val secondary = when (val res = repo.load(linked.secondaryUrl)) {
            is Resource.Success -> res.value
            else -> {
                Log.w(TAG, "[LINKED_SRC] entry load failed: $res")
                return false
            }
        }

        // (episode data, sub/dub tag) pairs to load links for
        val variants = mutableListOf<Pair<String, String?>>()
        if (page.isMovie()) {
            if (secondary !is MovieLoadResponse) {
                Log.w(
                    TAG,
                    "[LINKED_SRC] type mismatch: primary movie, secondary is " +
                            secondary.javaClass.simpleName
                )
                return false
            }
            variants += secondary.dataUrl to null
        } else {
            val pairs = mutableListOf<Pair<Episode, DubStatus?>>()
            when (secondary) {
                is AnimeLoadResponse -> secondary.episodes.forEach { (status, list) ->
                    list.forEach { pairs += it to status }
                }

                is TvSeriesLoadResponse -> secondary.episodes.forEach { pairs += it to null }

                else -> {
                    Log.w(
                        TAG,
                        "[LINKED_SRC] unsupported secondary type: " +
                                secondary.javaClass.simpleName
                    )
                    return false
                }
            }
            val exact = pairs.filter { (ep, _) ->
                ep.episode == current.episode &&
                        (ep.season ?: 1) == (current.season ?: 1)
            }
            // fallback: same episode number in any season (numbering drift)
            val matched =
                exact.ifEmpty { pairs.filter { it.first.episode == current.episode } }
            if (matched.isEmpty()) {
                Log.i(
                    TAG,
                    "[LINKED_SRC] no episode match for s${current.season}e${current.episode}"
                )
                return false
            }
            // both Sub and Dub variants of this episode exist → tag so the
            // link picker can tell them apart
            val ambiguous = matched.mapNotNull { it.second }.distinct().size > 1
            matched.forEach { (ep, status) ->
                variants += ep.data to
                        (if (ambiguous) (if (status == DubStatus.Subbed) "Sub" else "Dub") else null)
            }
        }

        var anyLoaded = false
        for ((data, tag) in variants) {
            val ok = repo.loadLinks(
                data,
                isCasting = isCasting,
                subtitleCallback = onSubtitle,
                callback = { link -> onLink(tagVariant(link, tag)) },
            )
            anyLoaded = anyLoaded || ok
            Log.i(TAG, "[LINKED_SRC] loadLinks ok=$ok tag=$tag data=$data")
        }
        return anyLoaded
    }

    /**
     * Appends " (Sub)"/" (Dub)" to ambiguous linked-source links — unless the
     * name already says it (one-line filter, no double tagging). Not suspend:
     * it runs inside loadLinks' plain callback lambda.
     */
    @Suppress("DEPRECATION")
    private fun tagVariant(link: ExtractorLink, tag: String?): ExtractorLink {
        if (tag == null) return link
        if (DUB_SUB_WORD.containsMatchIn(link.name)) return link
        return ExtractorLink(
            source = link.source,
            name = "${link.name} ($tag)",
            url = link.url,
            referer = link.referer,
            quality = link.quality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks,
        )
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