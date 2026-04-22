package com.lagradost.cloudstream3.mvvm

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData

/** NOTE: Only one observer at a time per value */
fun <T> LifecycleOwner.observe(liveData: LiveData<T>, action: (t: T) -> Unit) {
    android.util.Log.d("Lifecycle", "observe called for liveData: ${liveData.javaClass.simpleName}, lifecycleOwner: ${this.javaClass.simpleName}")
    liveData.removeObservers(this)
    android.util.Log.d("Lifecycle", "Removed previous observers, now adding new observer")
    liveData.observe(this) { 
        android.util.Log.d("Lifecycle", "Observer triggered with value: $it")
        it?.let { t -> action(t) } 
    }
}

/** NOTE: Only one observer at a time per value */
fun <T> LifecycleOwner.observeNullable(liveData: LiveData<T>, action: (t: T) -> Unit) {
    liveData.removeObservers(this)
    liveData.observe(this) { action(it) }
}
