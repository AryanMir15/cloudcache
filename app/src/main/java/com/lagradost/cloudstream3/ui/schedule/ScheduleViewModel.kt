package com.lagradost.cloudstream3.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.mvvm.launchSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val _scheduleItems = MutableLiveData<List<WeeklyScheduleItem>>()
    val scheduleItems: LiveData<List<WeeklyScheduleItem>> = _scheduleItems

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var allItems: List<WeeklyScheduleItem> = emptyList()
    private var currentFilter: ScheduleType? = null

    fun loadSchedule() = viewModelScope.launchSafe {
        val cached = withContext(Dispatchers.IO) {
            WeeklyScheduleManager.getCachedOrEmpty()
        }
        if (cached.isNotEmpty()) {
            allItems = cached
            applyFilter()
        }

        _isLoading.postValue(true)
        val fresh = withContext(Dispatchers.IO) {
            WeeklyScheduleManager.fetchFreshSchedule()
        }
        _isLoading.postValue(false)

        allItems = fresh
        applyFilter()
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
        val nullNames = filtered.filter { it.scheduleName.isBlank() || it.scheduleName == "null" }
        if (nullNames.isNotEmpty()) {
            android.util.Log.w("SCHEDULE_VM", "Found ${nullNames.size} items with blank/null names:")
            nullNames.forEach { android.util.Log.w("SCHEDULE_VM", "  id=${it.scheduleId} name=[${it.scheduleName}] poster=[${it.posterUrl}]") }
        }
        android.util.Log.d("SCHEDULE_VM", "Posting ${filtered.size} items (${allItems.size} total)")
        _scheduleItems.postValue(filtered)
    }
}
