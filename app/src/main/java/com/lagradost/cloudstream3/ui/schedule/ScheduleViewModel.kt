package com.lagradost.cloudstream3.ui.schedule

import android.app.Application
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

    private var loadJob: Job? = null

    fun loadSchedule() {
        loadJob?.cancel()
        loadJob = viewModelScope.launchSafe {
            _errorMessage.postValue(null)
            _statusMessage.postValue(null)

            // Phase 1: Always load cached items immediately → display them
            val cached = withContext(Dispatchers.IO) {
                WeeklyScheduleManager.loadFromCache()
            }

            if (cached.isNotEmpty()) {
                _scheduleItems.postValue(cached)
                _isLoading.postValue(false)
            } else {
                _isLoading.postValue(true)
            }

            // Phase 2: Check if cache is stale → fetch in background
            val cacheValid = withContext(Dispatchers.IO) {
                WeeklyScheduleManager.isCacheValid()
            }

            if (cacheValid && cached.isNotEmpty()) {
                _isLoading.postValue(false)
                return@launchSafe
            }

            // Background fetch: Anilist → TMDB enrichment → cache update
            val fresh = withContext(Dispatchers.IO) {
                try {
                    WeeklyScheduleManager.fetchFreshSchedule()
                } catch (e: Exception) {
                    null
                }
            }

            _isLoading.postValue(false)

            if (fresh != null && fresh.isNotEmpty() && fresh != cached) {
                // Merge: only emit if something actually changed
                _scheduleItems.postValue(fresh)
            } else if (cached.isEmpty() && fresh.isNullOrEmpty()) {
                _errorMessage.postValue("Could not load schedule")
            }
        }
    }
}
