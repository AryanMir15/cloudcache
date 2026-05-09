package com.lagradost.cloudstream3.ui.browse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Browse error states for UI feedback
 */
enum class BrowseError {
    NONE,
    MISSING_TMDB_KEY,
    NETWORK_ERROR,
    API_ERROR
}

/**
 * Extended filter state supporting both AniList and TMDB providers.
 * Fields are prefixed by provider to avoid conflicts when switching.
 */
data class BrowseFilterState(
    val provider: FilterProvider = FilterProvider.ANILIST,
    
    // AniList-specific fields (preserved when switching providers)
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val year: String = "All",
    val season: String = "All",
    val format: String = "All",
    
    // TMDB-specific fields
    val tmdbFormat: TmdbFormat = TmdbFormat.MOVIE,
    val tmdbGenres: Set<String> = emptySet(),  // Genre names (converted to IDs on API call)
    val tmdbExcludedGenres: Set<String> = emptySet(),
    val tmdbYear: String = "All",
    val tmdbCountry: String = "All",  // with_origin_country
    val tmdbProvider: String = "All",  // with_watch_providers
    val tmdbTrending: String = "Off",  // "Today" or "This Week", Off = use discover
    val tmdbIncludeAdult: Boolean = false,  // include_adult
    val tmdbKeywords: String = "",  // with_keywords (plain text)
    val tmdbMinVotes: Int = 0,  // vote_count.gte
    
    // Common
    val sort: String = "Popularity"
) {
    /**
     * Determines if paging should reset based on filter changes.
     * Resets on: provider change, TMDB format change, or significant filter change.
     */
    fun shouldResetPaging(other: BrowseFilterState): Boolean {
        return provider != other.provider ||
               (provider == FilterProvider.TMDB && tmdbFormat != other.tmdbFormat)
    }
}

/**
 * UI state for Browse tab with unified BrowseMediaItem type.
 */
data class BrowseUiState(
    val results: List<BrowseMediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val filters: BrowseFilterState = BrowseFilterState(),
    val isTmdbEnabled: Boolean = false,
    val error: BrowseError = BrowseError.NONE
)

class BrowseViewModel : ViewModel() {
    private val _uiState = MutableLiveData<BrowseUiState>()
    val uiState: LiveData<BrowseUiState> = _uiState

    init {
        _uiState.value = BrowseUiState()
    }

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value?.copy(isLoading = loading)
    }

    fun setTmdbEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value?.copy(isTmdbEnabled = enabled)
    }

    fun setError(error: BrowseError) {
        _uiState.value = _uiState.value?.copy(error = error)
    }

    fun clearError() {
        _uiState.value = _uiState.value?.copy(error = BrowseError.NONE)
    }

    fun updateResults(results: List<BrowseMediaItem>, hasMore: Boolean) {
        _uiState.value = _uiState.value?.copy(
            results = results,
            hasMore = hasMore,
            currentPage = 1,
            error = BrowseError.NONE
        )
    }

    fun appendResults(results: List<BrowseMediaItem>, hasMore: Boolean) {
        val currentResults = _uiState.value?.results ?: emptyList()
        _uiState.value = _uiState.value?.copy(
            results = currentResults + results,
            hasMore = hasMore,
            error = BrowseError.NONE
        )
    }

    fun incrementPage() {
        val currentPage = _uiState.value?.currentPage ?: 1
        _uiState.value = _uiState.value?.copy(currentPage = currentPage + 1)
    }

    fun resetPage() {
        _uiState.value = _uiState.value?.copy(currentPage = 1)
    }

    fun updateFilters(filters: BrowseFilterState) {
        val currentFilters = _uiState.value?.filters
        val shouldReset = currentFilters?.shouldResetPaging(filters) ?: false
        
        _uiState.value = _uiState.value?.copy(
            filters = filters,
            currentPage = if (shouldReset) 1 else (_uiState.value?.currentPage ?: 1)
        )
    }
}
