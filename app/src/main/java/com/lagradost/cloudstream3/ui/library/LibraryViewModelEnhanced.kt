package com.lagradost.cloudstream3.ui.library

import androidx.annotation.StringRes
import com.lagradost.cloudstream3.AppConstants
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.LocalLibraryCacheSimple
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.launch

const val PREFER_LOCAL_CACHE_KEY = "prefer_local_cache"

class LibraryViewModelEnhanced : ViewModel() {
    private val localLibraryCache = LocalLibraryCacheSimple(CloudStreamApp.context!!)
    
    fun switchPage(page: Int) {
        _currentPage.postValue(page)
    }

    private val _currentPage: MutableLiveData<Int> = MutableLiveData(0)
    val currentPage: LiveData<Int> = _currentPage

    private val _pages: MutableLiveData<Resource<List<SyncAPI.Page>>> = MutableLiveData(null)
    val pages: LiveData<Resource<List<SyncAPI.Page>>> = _pages

    private val _currentApiName: MutableLiveData<String> = MutableLiveData("")
    val currentApiName: LiveData<String> = _currentApiName

    private val availableSyncApis
        get() = AccountManager.syncApis.filter { it.isAvailable }

    var currentSyncApi = availableSyncApis.let { allApis ->
        val lastSelection = getKey<String>("$currentAccount/$LAST_SYNC_API_KEY")
        availableSyncApis.firstOrNull { it.name == lastSelection } ?: allApis.firstOrNull()
    }
        private set(value) {
            field = value
            setKey("$currentAccount/$LAST_SYNC_API_KEY", field?.name)
        }

    val availableApiNames: List<String>
        get() = availableSyncApis.map { it.name }

    var sortingMethods = emptyList<ListSorting>()
        private set

    var currentSortingMethod: ListSorting? = sortingMethods.firstOrNull()
        private set

    fun switchList(name: String) {
        currentSyncApi = availableSyncApis[availableApiNames.indexOf(name)]
        _currentApiName.postValue(currentSyncApi?.name)
        reloadPages(true)
    }

    fun sort(method: ListSorting, query: String? = null) = ioSafe {
        val value = _pages.value ?: return@ioSafe
        if (value is Resource.Success) {
            sort(method, query, value.value)
        }
    }

    private fun sort(method: ListSorting, query: String? = null, items: List<SyncAPI.Page>) {
        currentSortingMethod = method
        DataStoreHelper.librarySortingMode = method.ordinal

        when (method) {
            ListSorting.CacheFirst -> {
                // Load cached data first, then online data
                val cachedPages = loadCachedPages()
                val onlinePages = loadOnlinePages(query, items)
                val allPages = mergeCachedAndOnlinePages(cachedPages, onlinePages)
                _pages.postValue(Resource.Success(allPages))
            }
            else -> {
                // Original behavior - load from online API
                items.forEach { page ->
                    page.sort(method, query)
                }
                _pages.postValue(Resource.Success(items))
            }
        }
    }
    
    private fun loadCachedPages(): List<SyncAPI.Page> {
        val cachedSeries = localLibraryCache.getAllCachedSeries()
        return cachedSeries.map { series ->
            val episodes = localLibraryCache.getCachedEpisodes(series.id)
            val items = episodes.map { episode ->
                SyncAPI.LibraryItem(
                    name = episode.name,
                    url = "local://${episode.id}",
                    syncId = episode.id,
                    episodesCompleted = null,
                    episodesTotal = null,
                    personalRating = null,
                    lastUpdatedUnixTime = episode.cachedAt,
                    apiName = AppConstants.LOCAL_CACHE_PROVIDER,
                    type = TvType.TvSeries,
                    posterUrl = episode.posterPath,
                    posterHeaders = null,
                    quality = null,
                    releaseDate = null,
                    id = episode.id.toIntOrNull(),
                    plot = episode.description,
                    score = null,
                    tags = null
                )
            }
            SyncAPI.Page(
                title = txt(series.name),
                items = items
            )
        }
    }
    
    private fun loadOnlinePages(query: String?, items: List<SyncAPI.Page>): List<SyncAPI.Page> {
        val pages = items.map { SyncAPI.Page(it.title, it.items) }
        val desiredSortingMethod = ListSorting.entries.getOrNull(DataStoreHelper.librarySortingMode)
        if (desiredSortingMethod != null) {
            pages.forEach { page ->
                page.sort(desiredSortingMethod, query)
            }
        } else {
            pages.forEach { page ->
                page.sort(ListSorting.Query, null)
            }
        }
        return pages
    }
    
    private fun mergeCachedAndOnlinePages(
        cachedPages: List<SyncAPI.Page>,
        onlinePages: List<SyncAPI.Page>
    ): List<SyncAPI.Page> {
        val allPages = mutableMapOf<String, SyncAPI.Page>()
        
        // Add cached pages first
        cachedPages.forEach { page ->
            val key = page.title.asStringNull(CloudStreamApp.context)
            if (key != null) {
                allPages[key] = page
            }
        }
        
        // Add online pages that aren't cached
        onlinePages.forEach { page ->
            val key = page.title.asStringNull(CloudStreamApp.context)
            if (key != null && !allPages.containsKey(key)) {
                allPages[key] = page
            }
        }
        
        return allPages.values.toList()
    }
    
    // New method to cache series data
    fun cacheSeriesData(seriesId: String, searchResponse: com.lagradost.cloudstream3.SearchResponse) = viewModelScope.launch {
        try {
            // Episode fetching to be integrated with existing pipeline.
            localLibraryCache.cacheSeriesData(
                seriesId = seriesId,
                name = searchResponse.name,
                posterUrl = searchResponse.posterUrl,
                bannerUrl = null,
                description = null,
                rating = null,
                year = null,
                status = null,
                episodes = emptyList()
            )
        } catch (e: Exception) {
            // Handle error
        }
    }
    
    // New method to check if series is cached
    fun isSeriesCached(seriesId: String): Boolean {
        return localLibraryCache.getCachedSeries(seriesId) != null
    }
    
    // New method to get cached episode with file path
    fun getCachedEpisodeWithFile(seriesId: String, episodeId: String): com.lagradost.cloudstream3.utils.CachedEpisodeData? {
        val episodes = localLibraryCache.getCachedEpisodes(seriesId)
        return episodes.find { it.id == episodeId && it.filePath != null }
    }
    
    // New method to update episode file path
    fun updateEpisodeFilePath(seriesId: String, episodeId: String, filePath: String) = viewModelScope.launch {
        localLibraryCache.updateEpisodeFilePath(seriesId, source = "default", episodeId = episodeId, filePath = filePath)
    }
    
    // New method to clear cache for a series
    fun clearSeriesCache(seriesId: String) = viewModelScope.launch {
        localLibraryCache.clearCache(seriesId)
        reloadPages(true) // Reload to reflect cache clear
    }
    
    // New method to get total cache size
    fun getTotalCacheSize(): Long {
        return localLibraryCache.getTotalCacheSize()
    }

    fun reloadPages(forceReload: Boolean) {
        // Only skip loading if its not forced and pages is not empty
        if (!forceReload && (pages.value as? Resource.Success)?.value?.isNotEmpty() == true &&
            currentSyncApi?.requireLibraryRefresh != true
        ) return

        ioSafe {
            currentSyncApi?.let { repo ->
                _currentApiName.postValue(repo.name)
                _pages.postValue(Resource.Loading())
                val libraryResource = repo.library()
                val err = libraryResource.exceptionOrNull()
                if (err != null) {
                    _pages.postValue(throwAbleToResource(err))
                    return@ioSafe
                }
                val library = libraryResource.getOrNull()
                if (library == null) {
                    _pages.postValue(Resource.Failure(false, "Unable to fetch library"))
                    return@ioSafe
                }

                sortingMethods = library.supportedListSorting.toList()
                repo.requireLibraryRefresh = false

                val pages = library.allLibraryLists.map {
                    SyncAPI.Page(
                        it.name,
                        it.items
                    )
                }

                val desiredSortingMethod =
                    ListSorting.entries.getOrNull(DataStoreHelper.librarySortingMode)
                if (desiredSortingMethod != null && library.supportedListSorting.contains(
                        desiredSortingMethod
                    )
                ) {
                    sort(desiredSortingMethod, null, pages)
                } else {
                    // null query = no sorting
                    sort(ListSorting.Query, null, pages)
                }
            }
        }
    }

    init {
        MainActivity.reloadLibraryEvent += ::reloadPages
    }

    override fun onCleared() {
        MainActivity.reloadLibraryEvent -= ::reloadPages
        super.onCleared()
    }
}
