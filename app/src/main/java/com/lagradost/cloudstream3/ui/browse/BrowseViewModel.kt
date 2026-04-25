package com.lagradost.cloudstream3.ui.browse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.SearchResponse

data class BrowseFilterState(
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val year: String = "All",
    val season: String = "All",
    val format: String = "All",
    val sort: String = "Popularity"
)

data class BrowseUiState(
    val results: List<SearchResponse> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val filters: BrowseFilterState = BrowseFilterState()
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

    fun updateResults(results: List<SearchResponse>, hasMore: Boolean) {
        _uiState.value = _uiState.value?.copy(
            results = results,
            hasMore = hasMore,
            currentPage = 1
        )
    }

    fun appendResults(results: List<SearchResponse>, hasMore: Boolean) {
        val currentResults = _uiState.value?.results ?: emptyList()
        _uiState.value = _uiState.value?.copy(
            results = currentResults + results,
            hasMore = hasMore
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
        _uiState.value = _uiState.value?.copy(filters = filters)
    }
}
