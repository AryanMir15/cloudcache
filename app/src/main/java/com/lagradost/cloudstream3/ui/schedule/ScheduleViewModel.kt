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

    private var allItems: List<WeeklyScheduleItem> = emptyList()
    private var currentFilter: ScheduleType? = null
    private var loadJob: Job? = null

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
            if (tmdbKey.isNullOrBlank()) {
                _statusMessage.postValue("Configure TMDB API key in Settings for high quality banners")
            } else {
                _statusMessage.postValue("Fetching high quality banners from TMDB (may take ~1 min, running in background)...")
            }

            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeeklyScheduleManager.fetchFreshSchedule()
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
            if (tmdbKey.isNullOrBlank()) {
                _statusMessage.postValue("Configure TMDB API key in Settings for high quality banners")
            } else {
                _statusMessage.postValue("Refreshing schedule + banners from TMDB (may take ~1 min)...")
            }

            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeeklyScheduleManager.fetchFreshSchedule()
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

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            ScheduleType.ANIME -> allItems.filter { it.scheduleType == ScheduleType.ANIME }
            ScheduleType.TV -> allItems.filter { it.scheduleType == ScheduleType.TV }
            null -> allItems
        }
        _scheduleItems.postValue(filtered)
    }
}
