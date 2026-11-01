package com.lagradost.cloudstream3.ui.schedule

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.mvvm.launchSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val _scheduleItems = MutableLiveData<List<WeeklyScheduleItem>>()
    val scheduleItems: LiveData<List<WeeklyScheduleItem>> = _scheduleItems

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    private val _includeTvShows = MutableLiveData<Boolean>()
    val includeTvShows: LiveData<Boolean> = _includeTvShows

    private var allItems: List<WeeklyScheduleItem> = emptyList()
    private var currentFilter: ScheduleType? = null
    private var loadJob: Job? = null

    init {
        // Default to anime-only (false)
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication<Application>())
        _includeTvShows.postValue(prefs.getBoolean("schedule_include_tv_shows", false))
    }

    fun loadSchedule() {
        loadJob?.cancel()
        loadJob = viewModelScope.launchSafe {
            _errorMessage.postValue(null)
            _statusMessage.postValue(null)

            val cached = withContext(Dispatchers.IO) {
                WeeklyScheduleManager.getCachedOrEmpty()
            }
            val cacheValid = withContext(Dispatchers.IO) {
                WeeklyScheduleManager.isCacheValid()
            }

            if (cacheValid && cached.isNotEmpty()) {
                allItems = cached
                applyFilter()
                _isLoading.postValue(false)
                return@launchSafe
            }

            if (cached.isNotEmpty()) {
                allItems = cached
                applyFilter()
            } else {
                _isLoading.postValue(true)
            }

            // Check TMDB key before fetching
            val ctx = getApplication<Application>()
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val tmdbKey = prefs.getString("tmdb_api_key", null)
            val includeTv = _includeTvShows.value ?: false
            if (includeTv && tmdbKey.isNullOrBlank()) {
                _statusMessage.postValue("Configure TMDB API key in Settings for TV shows")
            } else if (includeTv) {
                _statusMessage.postValue("Fetching TV shows from TMDB (may take ~1 min, running in background)...")
            } else {
                _statusMessage.postValue("Anime-only mode (enable TV shows in filter menu)")
            }

            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeeklyScheduleManager.fetchFreshSchedule(includeTv) { enriched ->
                        // TMDB backdrops/logos resolved — swap in enriched items
                        allItems = enriched
                        applyFilter()
                    }
                } catch (e: Exception) {
                    null
                }
            }

            _isLoading.postValue(false)
            _statusMessage.postValue(null)

            if (fresh != null && fresh.isNotEmpty() && fresh != allItems) {
                allItems = fresh
                applyFilter()
            } else if (cached.isEmpty() && fresh.isNullOrEmpty()) {
                _errorMessage.postValue("Could not load schedule")
            }
        }
    }

    /**
     * Manual trigger: wipe all caches and pull a completely fresh schedule + TMDB
     * enrichment, bypassing the TTL / cache-valid short-circuit.
     */
    fun forceRefresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launchSafe {
            _errorMessage.postValue(null)
            _isLoading.postValue(true)

            withContext(Dispatchers.IO) {
                WeeklyScheduleManager.clearAllCaches()
            }
            allItems = emptyList()

            val ctx = getApplication<Application>()
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val tmdbKey = prefs.getString("tmdb_api_key", null)
            val includeTv = _includeTvShows.value ?: false
            if (includeTv && tmdbKey.isNullOrBlank()) {
                _statusMessage.postValue("Configure TMDB API key in Settings for TV shows")
            } else if (includeTv) {
                _statusMessage.postValue("Refreshing schedule + TV shows from TMDB (may take ~1 min)...")
            } else {
                _statusMessage.postValue("Refreshing anime-only schedule...")
            }

            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeeklyScheduleManager.fetchFreshSchedule(includeTv) { enriched ->
                        allItems = enriched
                        applyFilter()
                    }
                } catch (e: Exception) {
                    null
                }
            }

            _isLoading.postValue(false)
            _statusMessage.postValue(null)

            if (fresh != null && fresh.isNotEmpty()) {
                allItems = fresh
                applyFilter()
            } else {
                _errorMessage.postValue("Could not load schedule")
            }
        }
    }

    fun setFilter(type: ScheduleType?) {
        currentFilter = type
        applyFilter()
    }

    fun setIncludeTvShows(include: Boolean) {
        val ctx = getApplication<Application>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean("schedule_include_tv_shows", include)
            .apply()
        _includeTvShows.postValue(include)
        applyFilter()
    }

    private fun applyFilter() {
        val includeTv = _includeTvShows.value ?: false
        val baseItems = if (includeTv) {
            allItems
        } else {
            allItems.filter { it.scheduleType == ScheduleType.ANIME }
        }

        val filtered = when (currentFilter) {
            ScheduleType.ANIME -> baseItems.filter { it.scheduleType == ScheduleType.ANIME }
            ScheduleType.TV -> baseItems.filter { it.scheduleType == ScheduleType.TV }
            null -> baseItems
        }

        // Sort: anime first (false=0), then TV (true=1), then by airing time
        val sorted = filtered.sortedWith(
            compareBy<WeeklyScheduleItem> { it.scheduleType != ScheduleType.ANIME }
                .thenBy { it.airingAt }
        )
        _scheduleItems.postValue(sorted)
    }
}
