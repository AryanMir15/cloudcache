package com.lagradost.cloudstream3.mvvm

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData

/** NOTE: Only one observer at a time per value */
fun <T> LifecycleOwner.observe(liveData: LiveData<T>, action: (t: T) -> Unit) {
    android.util.Log.d("Lifecycle", "observe called for liveData: ${liveData.javaClass.simpleName}, lifecycleOwner: ${this.javaClass.simpleName}")
    liveData.removeObservers(this)
    android.util.Log.d("Lifecycle", "Removed previous observers, now adding new observer")
    liveData.observe(this) { 
        android.util.Log.d("Lifecycle", "Observer triggered - thread: ${Thread.currentThread().name}, value class: ${it?.javaClass?.simpleName}, value is Map: ${it is Map<*, *>}")
        try {
            android.util.Log.d("Lifecycle", "Observer triggered with value: $it")
        } catch (e: Exception) {
            android.util.Log.e("Lifecycle", "ERROR logging value in observer: ${e.javaClass.simpleName}: ${e.message}", e)
            android.util.Log.e("Lifecycle", "Value that caused error: class=${it?.javaClass?.simpleName}, toString available=${it != null}")
        }
        it?.let { t -> action(t) } 
    }
}

/** NOTE: Only one observer at a time per value */
fun <T> LifecycleOwner.observeNullable(liveData: LiveData<T>, action: (t: T) -> Unit) {
    liveData.removeObservers(this)
    liveData.observe(this) { action(it) }
}
