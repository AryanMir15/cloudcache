package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadPlaybackGate
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerGeneratorViewModel : ViewModel() {
    companion object {
        const val TAG = "PlayViewGen"
        private const val AUTO_NEXT_LOG = "AUTO_NEXT_DL"
    }

    private var generator: IGenerator? = null

    private val _currentLinks = MutableLiveData<Set<Pair<ExtractorLink?, ExtractorUri?>>>(setOf())
    val currentLinks: LiveData<Set<Pair<ExtractorLink?, ExtractorUri?>>> = _currentLinks

    private val _currentSubs = MutableLiveData<Set<SubtitleData>>(setOf())
    val currentSubs: LiveData<Set<SubtitleData>> = _currentSubs

    private val _loadingLinks = MutableLiveData<Resource<Boolean?>>()
    val loadingLinks: LiveData<Resource<Boolean?>> = _loadingLinks

    private val _currentStamps = MutableLiveData<List<VideoSkipStamp>>(emptyList())
    val currentStamps: LiveData<List<VideoSkipStamp>> = _currentStamps

    private val _currentSubtitleYear = MutableLiveData<Int?>(null)
    val currentSubtitleYear: LiveData<Int?> = _currentSubtitleYear

    /**
     * Save the Episode ID to prevent starting multiple link loading Jobs when preloading links.
     */
    private var currentLoadingEpisodeId: Int? = null

    var forceClearCache = false

    fun setSubtitleYear(year: Int?) {
        _currentSubtitleYear.postValue(year)
    }

    fun getId(): Int? {
        return generator?.getCurrentId()
    }

    fun loadLinks(episode: Int) {
        generator?.goto(episode)
        loadLinks()
    }

    fun loadLinksPrev() {
        Log.i(TAG, "loadLinksPrev")
        if (generator?.hasPrev() == true) {
            generator?.prev()
            loadLinks()
        }
    }

    fun loadLinksNext() {
        Log.i(TAG, "loadLinksNext")
        if (generator?.hasNext() == true) {
            generator?.next()
            loadLinks()
        }
    }

    fun hasNextEpisode(): Boolean? {
        return generator?.hasNext()
    }

    fun hasPrevEpisode(): Boolean? {
        return generator?.hasPrev()
    }

    fun preLoadNextLinks() {
        val id = getId()
        // Do not preload if already loading
        if (id == currentLoadingEpisodeId) return

        Log.i(TAG, "preLoadNextLinks")
        currentJob?.cancel()
        currentLoadingEpisodeId = id

        currentJob = viewModelScope.launch {
            try {
                if (generator?.hasCache == true && generator?.hasNext() == true) {
                    safeApiCall {
                        generator?.generateLinks(
                            sourceTypes = LOADTYPE_INAPP,
                            clearCache = false,
                            callback = {},
                            subtitleCallback = {},
                            offset = 1
                        )
                    }
                }
            } catch (t: Throwable) {
                logError(t)
            } finally {
                if (currentLoadingEpisodeId == id) {
                    currentLoadingEpisodeId = null
                }
            }
        }
    }

    /** Episode ids already considered for one-ahead caching this session. */
    private val autoQueuedEpisodeIds = mutableSetOf<Int>()

    /**
     * One-ahead caching: once the episode being watched is fully on disk,
     * enqueue the next one through the normal download queue (the queue
     * service resolves the links itself and applies the quality/language
     * download preferences).
     *
     * Triggered from GeneratorPlayer at play start, when the watched
     * episode's download hits IsDone, and at the 80% preload point. Runs on
     * the main dispatcher so the gate's disk reads and the dedup set stay
     * single-threaded; repeated calls are cheap no-ops after the first queue.
     */
    fun maybeAutoQueueNextEpisode() {
        viewModelScope.launch { autoQueueNextEpisode() }
    }

    private suspend fun autoQueueNextEpisode() {
        try {
            if (!DataStoreHelper.autoDownloadNextEpisode) return
            if (generator?.hasNext() != true) return
            val next = getNextMeta() as? ResultEpisode ?: return
            if (next.tvType.isLiveStream()) return
            val currentId = getId() ?: return
            val ctx = CloudStreamApp.context ?: return
            val page = getLoadResponse() ?: return

            // Trigger: the episode being watched must be fully cached. The gate
            // re-verifies status + exact size + media3 sniff, so even a bogus
            // IsDone (filename-scan re-marks) cannot start the chain.
            if (DownloadPlaybackGate.check(ctx, currentId, AUTO_NEXT_LOG) !=
                DownloadPlaybackGate.PlayableState.Ready
            ) {
                return
            }

            // Honor the shared auto-download network preference (wifi_only by
            // default). Not terminal: a later trigger still queues it.
            if (!isNetworkAllowedForAutoDownload(ctx)) {
                Log.i(TAG, "$AUTO_NEXT_LOG deferred ep ${next.episode} — network not allowed")
                return
            }

            // Terminal for this session: cached / queued / downloading already
            if (!autoQueuedEpisodeIds.add(next.id)) return
            if (DownloadPlaybackGate.check(ctx, next.id, AUTO_NEXT_LOG) !=
                DownloadPlaybackGate.PlayableState.NotReady
            ) {
                return
            }

            DownloadQueueManager.addToQueue(
                DownloadObjects.DownloadQueueItem(
                    next,
                    page.isMovie(),
                    page.name,
                    page.type,
                    page.posterUrl,
                    page.apiName,
                    page.getId(),
                    page.url,
                    dubStatus = next.dubStatus,
                ).toWrapper()
            )
            Log.i(
                TAG,
                "$AUTO_NEXT_LOG queued ep ${next.episode} id=${next.id} for '${page.name}'"
            )
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Mirrors EpisodeCheckWorkManager's guard; preference defaults to wifi_only. */
    private fun isNetworkAllowedForAutoDownload(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            when (DataStoreHelper.autoDownloadNetworkPreference) {
                "wifi_only" -> isWifi
                "data_only" -> isMobile
                "both" -> isWifi || isMobile
                else -> isWifi
            }
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    fun getLoadResponse(): LoadResponse? {
        return safe { (generator as? RepoLinkGenerator?)?.page }
    }

    fun getMeta(): Any? {
        return safe { generator?.getCurrent() }
    }

    fun getAllMeta(): List<Any>? {
        return safe { generator?.getAll() }
    }

    fun getNextMeta(): Any? {
        return safe {
            if (generator?.hasNext() == false) return@safe null
            generator?.getCurrent(offset = 1)
        }
    }

    fun loadThisEpisode(index:Int) {
        android.util.Log.d("PlayerGeneratorViewModel", "loadThisEpisode: index=$index")
        val currentBefore = generator?.getCurrent()
        android.util.Log.d("PlayerGeneratorViewModel", "Before goto: current=$currentBefore")
        generator?.goto(index)
        val currentAfter = generator?.getCurrent()
        android.util.Log.d("PlayerGeneratorViewModel", "After goto: current=$currentAfter")
        loadLinks()
    }

    fun getCurrentIndex():Int?{
        val repoGen = generator as? RepoLinkGenerator ?: return null
        return repoGen.videoIndex
    }

    fun attachGenerator(newGenerator: IGenerator?) {
        if (generator == null) {
            generator = newGenerator
        }
    }

    private var extraSubtitles : MutableSet<SubtitleData> = mutableSetOf()

    /**
     * If duplicate nothing will happen
     * */
    fun addSubtitles(file: Set<SubtitleData>) = synchronized(extraSubtitles) {
        extraSubtitles += file
        val current = _currentSubs.value ?: emptySet()
        val next = extraSubtitles + current

        // if it is of a different size then we have added distinct items
        if (next.size != current.size) {
            // Posting will refresh subtitles which will in turn
            // make the subs to english if previously unselected
            _currentSubs.postValue(next)
        }
    }

    private var currentJob: Job? = null
    private var currentStampJob: Job? = null

    fun loadStamps(duration: Long) {
        //currentStampJob?.cancel()
        currentStampJob = ioSafe {
            val meta = generator?.getCurrent()
            val page = (generator as? RepoLinkGenerator?)?.page
            if (page != null && meta is ResultEpisode) {
                _currentStamps.postValue(listOf())
                _currentStamps.postValue(
                    SkipAPI.videoStamps(
                        page,
                        meta,
                        duration,
                        hasNextEpisode() ?: false
                    )
                )
            }
        }
    }

    fun loadLinks(sourceTypes: Set<ExtractorLinkType> = LOADTYPE_INAPP) {
        Log.i(TAG, "loadLinks")
        currentJob?.cancel()

        currentJob = viewModelScope.launchSafe {
            // if we load links then we clear the prev loaded links
            synchronized(extraSubtitles) {
                extraSubtitles.clear()
            }
            val currentLinks = mutableSetOf<Pair<ExtractorLink?, ExtractorUri?>>()
            val currentSubs = mutableSetOf<SubtitleData>()

            // clear old data
            _currentSubs.postValue(emptySet())
            _currentLinks.postValue(emptySet())

            // load more data
            _loadingLinks.postValue(Resource.Loading())
            val loadingState = safeApiCall {
                generator?.generateLinks(
                    sourceTypes = sourceTypes,
                    clearCache = forceClearCache,
                    callback = {
                        synchronized(currentLinks) {
                            currentLinks.add(it)
                            // Clone to prevent ConcurrentModificationException
                            safe {
                                // Extra safe since .toSet() iterates.
                                _currentLinks.postValue(currentLinks.toSet())
                            }
                        }
                    },
                    subtitleCallback = {
                        synchronized(extraSubtitles) {
                            currentSubs.add(it)
                            safe {
                                _currentSubs.postValue(currentSubs + extraSubtitles)
                            }
                        }
                    })
            }

            _loadingLinks.postValue(loadingState)
            _currentLinks.postValue(currentLinks)
            synchronized(extraSubtitles) {
                _currentSubs.postValue(currentSubs + extraSubtitles)
            }
        }

    }
}