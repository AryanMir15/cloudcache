package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.LinkedSourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Secondary LoadResponse cache for [mergeLinkedSource] — url → (response, unixTime). */
    private val linkedResponseCache = HashMap<String, Pair<LoadResponse, Long>>()

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
        // The two sources run CONCURRENTLY, so each closure's ENTIRE body is
        // guarded by the shared cache lock (dedup sets + suffix counters too).
        val onSubtitle: (SubtitleFile) -> Unit = sub@ { file ->
            Log.d(TAG, "Loaded SubtitleFile: $file")
            // Mutate under the lock, emit outside it — the player callbacks do
            // UI/link work that must not serialize both sources on the cache
            // lock (or risk reentrant deadlock).
            val updatedFile = synchronized(currentCache) {
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || currentSubsUrls.contains(correctFile.url)) {
                    return@sub
                }
                currentSubsUrls.add(correctFile.url)

                // this part makes sure that all names are unique for UX

                val nameDecoded = correctFile.originalName.html().toString().trim() // decoded html → plain name

                val suffixCount = lastCountedSuffix.getOrDefault(nameDecoded, 0u) +1u
                lastCountedSuffix[nameDecoded] = suffixCount

                val candidate =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                if (currentCache.subtitleCache.add(candidate)) {
                    currentCache.lastCachedTimestamp = unixTime
                    candidate
                } else null
            } ?: return@sub
            subtitleCallback(updatedFile)
        }

        val onLink: (ExtractorLink) -> Unit = link@ { link ->
            Log.d(TAG, "Loaded ExtractorLink: $link")
            val emit = synchronized(currentCache) {
                if (link.url.isBlank() || currentLinksUrls.contains(link.url)) {
                    return@link
                }
                currentLinksUrls.add(link.url)

                if (currentCache.linkCache.add(link)) {
                    currentCache.lastCachedTimestamp = unixTime
                    sourceTypes.contains(link.type)
                } else false
            }
            if (emit) callback(Pair(link, null))
        }

        // Both sources load CONCURRENTLY: the linked source starts first so
        // its entry resolution overlaps the primary crawl, and links from
        // either stream live through the shared handlers — never batched.
        return coroutineScope {
            val linkedJob = async {
                try {
                    mergeLinkedSource(current, isCasting, onSubtitle, onLink)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "[LINKED_SRC] merge failed", t)
                    false
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

            val linkedResult = linkedJob.await()

            synchronized(currentCache) {
                currentCache.saturated = currentCache.linkCache.isNotEmpty()
                currentCache.lastCachedTimestamp = unixTime
            }

            result || linkedResult
        }
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
        // Player path: full page. Download path: no page — resolve through the
        // episode's provider + the queued result URL, which is the same
        // LinkedSourceManager key (primaryApiName|primaryUrl). Without this,
        // link-less downloads only ever see native provider links and the
        // preferred source (from a merged secondary provider) never appears.
        val linked = page?.let { LinkedSourceManager.get(it.apiName, it.url) }
            ?: resultUrl?.let { LinkedSourceManager.get(current.apiName, it) }
            ?: return false
        if (page != null && linked.secondaryApiName.equals(page.apiName, ignoreCase = true) &&
            linked.secondaryUrl == page.url
        ) {
            return false
        }
        Log.i(
            TAG,
            "[LINKED_SRC] ${page?.apiName ?: current.apiName} -> ${linked.secondaryApiName}" +
                    " (${linked.secondaryName})"
        )
        val api = getApiFromNameNull(linked.secondaryApiName) ?: run {
            Log.w(TAG, "[LINKED_SRC] provider not installed: ${linked.secondaryApiName}")
            return false
        }
        val repo = APIRepository(api)
        // Full page loads are expensive — reuse the secondary LoadResponse for
        // the link-cache TTL (20min) so every episode doesn't re-fetch it.
        val responseCacheKey = "${linked.secondaryApiName}|${linked.secondaryUrl}"
        val secondary = synchronized(linkedResponseCache) {
            linkedResponseCache[responseCacheKey]
                ?.takeIf { unixTime - it.second < 60 * 20 }
                ?.first
        } ?: run {
            when (val res = repo.load(linked.secondaryUrl)) {
                is Resource.Success -> res.value.also { loaded ->
                    synchronized(linkedResponseCache) {
                        linkedResponseCache[responseCacheKey] = loaded to unixTime
                    }
                }

                else -> {
                    Log.w(TAG, "[LINKED_SRC] entry load failed: $res")
                    return false
                }
            }
        }

        // (episode data, sub/dub tag) pairs to load links for
        val variants = mutableListOf<Pair<String, String?>>()
        val isMovie = page?.isMovie() ?: (current.tvType == TvType.Movie)
        if (isMovie) {
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
                        (if (ambiguous) when (status) {
                            DubStatus.Subbed -> "Sub"
                            DubStatus.Dubbed -> "Dub"
                            else -> null
                        } else null)
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
}