package com.lagradost.cloudstream3.ui.result

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.kitsuApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.malApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.simklApi
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.SyncUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


data class CurrentSynced(
    val name: String,
    val idPrefix: String,
    val isSynced: Boolean,
    val hasAccount: Boolean,
    val icon: Int?,
)

class SyncViewModel : ViewModel() {
    companion object {
        const val TAG = "SYNCVM"
    }

    private val repos = AccountManager.syncApis

    private val _metaResponse: MutableLiveData<Resource<SyncAPI.SyncResult>?> =
        MutableLiveData(null)

    val metadata: LiveData<Resource<SyncAPI.SyncResult>?> = _metaResponse

    private val _userDataResponse: MutableLiveData<Resource<SyncAPI.AbstractSyncStatus>?> =
        MutableLiveData(null)

    val userData: LiveData<Resource<SyncAPI.AbstractSyncStatus>?> = _userDataResponse

    // prefix, id
    // [SIMKL_DEFINITIVE_FIX][PHASE3] Mutex-protected syncs map for thread safety
    private val syncsMutex = Mutex()
    private val syncs = mutableMapOf<String, String>()
    
    // [RACE_CONDITION_FIX] Track in-flight requests to prevent overlapping calls
    private val isFetchingUserData = AtomicBoolean(false)
    private var lastRequestedSyncs: Map<String, String> = emptyMap()

    
    // StateFlow for reactive sync updates
    private val _syncsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val syncsFlow: StateFlow<Map<String, String>> = _syncsFlow

    fun getSyncs(): Map<String, String> {
        return syncs.toMap()
    }

    private val _currentSynced: MutableLiveData<List<CurrentSynced>> =
        MutableLiveData(getMissing())

    // pair of name idPrefix isSynced
    val synced: LiveData<List<CurrentSynced>> = _currentSynced

    private fun getMissing(): List<CurrentSynced> {
        return repos.map {
            CurrentSynced(
                it.name,
                it.idPrefix,
                syncs.containsKey(it.idPrefix),
                it.authUser() != null,
                it.icon,
            )
        }
    }

    fun updateSynced() {
        Log.i(TAG, "updateSynced - current syncs: ${syncs.keys}")
        val missing = getMissing()
        Log.i(TAG, "updateSynced - getMissing result: ${missing.map { "${it.name}(isSynced=${it.isSynced})" }}")
        
        // CRASH PROTECTION: Only update if Fragment is still attached to prevent data loss during crashes
        if (_currentSynced.value != null) {
            // Use setValue on main thread for immediate updates, postValue on background thread
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                _currentSynced.value = missing
                Log.i(TAG, "updateSynced - _currentSynced.value set (main thread) to ${missing.size} items")
            } else {
                _currentSynced.postValue(missing)
                Log.i(TAG, "updateSynced - _currentSynced.postValue called (background thread) with ${missing.size} items")
            }
        } else {
            Log.w(TAG, "updateSynced - SKIPPED: _currentSynced.value is null, possible Fragment crash in progress")
        }
    }

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Mutex-protected addSync for atomic updates
    private suspend fun addSync(idPrefix: String, id: String): Boolean {
        syncsMutex.withLock {
            if (syncs[idPrefix] == id) return false
            Log.i(TAG, "addSync $idPrefix = $id")
            syncs[idPrefix] = id
            _syncsFlow.value = syncs.toMap()
            return true
        }
    }

    // Keep non-suspend version for compatibility, but mark as blocking
    private fun addSyncBlocking(idPrefix: String, id: String): Boolean {
        if (syncs[idPrefix] == id) return false
        Log.i(TAG, "addSyncBlocking $idPrefix = $id")
        syncs[idPrefix] = id
        _syncsFlow.value = syncs.toMap()
        return true
    }

    fun clearUrlCache() {
        Log.i(TAG, "clearUrlCache - clearing ${hasAddedFromUrl.size} cached URLs")
        hasAddedFromUrl.clear()
    }

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Thread-safe addSyncs with Mutex
    suspend fun addSyncs(map: Map<String, String>?): Boolean {
        Log.i(TAG, "addSyncs called with: $map")

        return syncsMutex.withLock {
            Log.i(TAG, "addSyncs current syncs map: $syncs")
            var isValid = false

            map?.forEach { (prefix, id) ->
                if (syncs[prefix] != id) {
                    Log.i(TAG, "addSyncs - adding $prefix = $id")
                    syncs[prefix] = id
                    isValid = true
                }
            }

            if (isValid) {
                _syncsFlow.value = syncs.toMap()
            }

            Log.i(TAG, "addSyncs final result: $isValid, syncs now: $syncs")
            isValid
        }
    }

    
    // Non-blocking version for Java interop and legacy code
    fun addSyncsBlocking(map: Map<String, String>?): Boolean {
        Log.i(TAG, "addSyncsBlocking called with: $map")
        Log.i(TAG, "addSyncsBlocking current syncs map: $syncs")
        var isValid = false

        map?.forEach { (prefix, id) ->
            val added = addSyncBlocking(prefix, id)
            Log.i(TAG, "addSyncsBlocking - addSync($prefix, $id) returned: $added")
            isValid = added || isValid
        }
        Log.i(TAG, "addSyncsBlocking final result: $isValid, syncs now: $syncs")
        return isValid
    }

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Suspend versions with Mutex protection
    private suspend fun setMalId(id: String?): Boolean {
        return addSync(malApi.idPrefix, id ?: return false)
    }

    private suspend fun setAniListId(id: String?): Boolean {
        return addSync(aniListApi.idPrefix, id ?: return false)
    }

    var hasAddedFromUrl: HashSet<String> = hashSetOf()

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Thread-safe addFromUrl with Mutex
    fun addFromUrl(url: String?) = ioSafe {
        Log.i(TAG, "addFromUrl = $url")
        Log.i(TAG, "hasAddedFromUrl contains url: ${hasAddedFromUrl.contains(url)}")
        Log.i(TAG, "hasAddedFromUrl size: ${hasAddedFromUrl.size}")
        Log.i(TAG, "url starts with http: ${url?.startsWith("http")}")

        if (url == null) {
            Log.i(TAG, "addFromUrl - url is null, returning")
            return@ioSafe
        }

        syncsMutex.withLock {
            if (hasAddedFromUrl.contains(url)) {
                Log.i(TAG, "addFromUrl - url already added, returning")
                return@ioSafe
            }
            if (!url.startsWith("http")) {
                Log.i(TAG, "addFromUrl - url doesn't start with http, returning")
                return@ioSafe
            }
            hasAddedFromUrl.add(url)
        }

        SyncUtil.getIdsFromUrl(url)?.let { (malId, aniListId) ->
            var hasAdded = false

            malId?.let { id ->
                if (setMalId(id)) hasAdded = true
            }
            aniListId?.let { id ->
                if (setAniListId(id)) hasAdded = true
            }

            if (hasAdded) {
                updateSynced()
                Log.i(TAG, "addFromUrl->updateMetaAndUser $malId $aniListId")
                updateMetaAndUser()
            }
        }
    }

    fun setEpisodesDelta(delta: Int) {
        Log.i(TAG, "setEpisodesDelta = $delta")

        val user = userData.value
        if (user is Resource.Success) {
            user.value.watchedEpisodes?.plus(
                delta
            )?.let { episode ->
                setEpisodes(episode)
            }
        }
    }

    fun setEpisodes(episodes: Int) {
        Log.i(TAG, "setEpisodes = $episodes")

        if (episodes < 0) return
        val meta = metadata.value
        if (meta is Resource.Success) {
            meta.value.totalEpisodes?.let { max ->
                if (episodes > max) {
                    setEpisodes(max)
                    return
                }
            }
        }

        val user = userData.value
        if (user is Resource.Success) {
            // Create immutable copy with new episodes to ensure UI updates
            val currentUser = user.value
            val updatedUser = when (currentUser) {
                is com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus -> {
                    com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus(
                        status = currentUser.status,
                        score = currentUser.score,
                        oldScore = currentUser.oldScore,
                        watchedEpisodes = episodes,
                        episodeConstructor = currentUser.episodeConstructor,
                        isFavorite = currentUser.isFavorite,
                        maxEpisodes = currentUser.maxEpisodes,
                        oldEpisodes = currentUser.oldEpisodes,
                        oldStatus = currentUser.oldStatus
                    )
                }
                else -> {
                    // For other sync providers, try to use copy if available, or fallback to modifying
                    try {
                        // Try to use reflection for copy() method if it's a data class
                        val copyMethod = currentUser?.javaClass?.getMethod("copy")
                        if (copyMethod != null) {
                            val copy = copyMethod.invoke(currentUser)
                            val episodesField = copy?.javaClass?.getDeclaredField("watchedEpisodes")
                            episodesField?.isAccessible = true
                            episodesField?.set(copy, episodes)
                            copy as SyncAPI.AbstractSyncStatus
                        } else {
                            // Fallback: modify and post same reference (forces UI update)
                            currentUser.watchedEpisodes = episodes
                            currentUser
                        }
                    } catch (e: Exception) {
                        // Fallback: modify and post same reference
                        currentUser.watchedEpisodes = episodes
                        currentUser
                    }
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        }
    }

    fun setScore(score: Score?) {
        Log.i(TAG, "setScore = $score")
        val user = userData.value
        if (user is Resource.Success) {
            // Create immutable copy with new score to ensure UI updates
            val currentUser = user.value
            val updatedUser = when (currentUser) {
                is com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus -> {
                    com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus(
                        status = currentUser.status,
                        score = score,
                        oldScore = currentUser.oldScore,
                        watchedEpisodes = currentUser.watchedEpisodes,
                        episodeConstructor = currentUser.episodeConstructor,
                        isFavorite = currentUser.isFavorite,
                        maxEpisodes = currentUser.maxEpisodes,
                        oldEpisodes = currentUser.oldEpisodes,
                        oldStatus = currentUser.oldStatus
                    )
                }
                else -> {
                    // For other sync providers, try to use copy if available, or fallback to modifying
                    try {
                        // Try to use reflection for copy() method if it's a data class
                        val copyMethod = currentUser?.javaClass?.getMethod("copy")
                        if (copyMethod != null) {
                            val copy = copyMethod.invoke(currentUser)
                            val scoreField = copy?.javaClass?.getDeclaredField("score")
                            scoreField?.isAccessible = true
                            scoreField?.set(copy, score)
                            copy as SyncAPI.AbstractSyncStatus
                        } else {
                            // Fallback: modify and post same reference (forces UI update)
                            currentUser.score = score
                            currentUser
                        }
                    } catch (e: Exception) {
                        // Fallback: modify and post same reference
                        currentUser.score = score
                        currentUser
                    }
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        }
    }

    fun setStatus(which: Int) {
        Log.i(TAG, "setStatus = $which")
        if (which < -1 || which > 5) return // validate input
        val user = userData.value
        if (user is Resource.Success) {
            // Create immutable copy with new status to ensure UI updates
            val currentUser = user.value
            val newStatus = SyncWatchType.fromInternalId(which)
            val updatedUser = when (currentUser) {
                is com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus -> {
                    com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus(
                        status = newStatus,
                        score = currentUser.score,
                        oldScore = currentUser.oldScore,
                        watchedEpisodes = currentUser.watchedEpisodes,
                        episodeConstructor = currentUser.episodeConstructor,
                        isFavorite = currentUser.isFavorite,
                        maxEpisodes = currentUser.maxEpisodes,
                        oldEpisodes = currentUser.oldEpisodes,
                        oldStatus = currentUser.oldStatus
                    )
                }
                else -> {
                    // For other sync providers, try to use copy if available, or fallback to modifying
                    try {
                        // Try to use reflection for copy() method if it's a data class
                        val copyMethod = currentUser?.javaClass?.getMethod("copy")
                        if (copyMethod != null) {
                            val copy = copyMethod.invoke(currentUser)
                            val statusField = copy?.javaClass?.getDeclaredField("status")
                            statusField?.isAccessible = true
                            statusField?.set(copy, newStatus)
                            copy as SyncAPI.AbstractSyncStatus
                        } else {
                            // Fallback: modify and post same reference (forces UI update)
                            currentUser.status = newStatus
                            currentUser
                        }
                    } catch (e: Exception) {
                        // Fallback: modify and post same reference
                        currentUser.status = newStatus
                        currentUser
                    }
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        }
    }

    fun publishUserData() = ioSafe {
        Log.i(TAG, "publishUserData")
        val user = userData.value
        if (user is Resource.Success) {
            syncs.forEach { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.updateStatus(id, user.value)
            }
        }
        updateUserData()
    }

    fun modifyMaxEpisode(episodeNum: Int) {
        Log.i(TAG, "modifyMaxEpisode = $episodeNum")
        modifyData { status ->
            status.watchedEpisodes = maxOf(
                episodeNum,
                status.watchedEpisodes ?: return@modifyData null
            )
            status
        }
    }

    /// modifies the current sync data, return null if you don't want to change it
    private fun modifyData(update: ((SyncAPI.AbstractSyncStatus) -> (SyncAPI.AbstractSyncStatus?))) =
        ioSafe {
            syncs.amap { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                    val result =
                        update(repo.status(id).getOrNull() ?: return@let null) ?: return@let null
                    Log.i(TAG, "modifyData ${repo.name} => $result")
                    repo.updateStatus(id, result)
                }
            }
        }

    fun updateUserData() {
        Log.i(TAG, "updateUserData - syncs size: ${syncs.size}, syncs: $syncs")
        
        // [RACE_CONDITION_FIX] Prevent overlapping calls
        val currentSyncs = syncs.toMap()
        if (isFetchingUserData.get()) {
            // If same data is being fetched, skip entirely
            if (currentSyncs == lastRequestedSyncs) {
                Log.i(TAG, "updateUserData - SKIPPED: identical request already in progress")
                return
            }
        }
        
        // Try to acquire the flag - if already true, another call is in progress
        if (!isFetchingUserData.compareAndSet(false, true)) {
            Log.i(TAG, "updateUserData - SKIPPED: another request is in progress")
            return
        }
        
        lastRequestedSyncs = currentSyncs
        
        viewModelScope.launch {
            try {
                _userDataResponse.postValue(Resource.Loading())

            var triedApis = 0
            var successApi: String? = null
            val status = syncs.firstNotNullOfOrNull { (prefix, id) ->
                triedApis++
                Log.i(TAG, "updateUserData - trying $prefix with id $id")
                val repo = repos.firstOrNull { it.idPrefix == prefix }
                Log.i(TAG, "updateUserData - repo for $prefix: ${repo != null}")
                val result = repo?.status(id)
                Log.i(TAG, "updateUserData - status result for $prefix: isSuccess=${result?.isSuccess}, isFailure=${result?.isFailure}")
                val statusValue = result?.getOrNull()
                if (statusValue != null) {
                    successApi = prefix
                    Log.i(TAG, "updateUserData - SUCCESS for $prefix")
                }
                statusValue
            }

            Log.i(TAG, "updateUserData - tried $triedApis APIs, success: $successApi, status is null: ${status == null}")

                if (status == null) {
                    _userDataResponse.postValue(Resource.Failure(false, "No data"))
                } else {
                    _userDataResponse.postValue(Resource.Success(status))
                }
            } finally {
                // [RACE_CONDITION_FIX] Always release the flag
                isFetchingUserData.set(false)
                Log.i(TAG, "updateUserData - request completed, flag released")
            }
        }
    }

    private fun updateMetadata() = ioSafe {
        Log.i(TAG, "updateMetadata")

        _metaResponse.postValue(Resource.Loading())
        var lastError: Resource<SyncAPI.SyncResult> = Resource.Failure(false, "No data")
        val current = ArrayList(syncs.toList())

        // shitty way to sort anilist first, as it has trailers while mal does not
        if (syncs.containsKey(aniListApi.idPrefix)) {
            try { // swap can throw error
                Collections.swap(
                    current,
                    current.indexOfFirst { it.first == aniListApi.idPrefix },
                    0
                )
            } catch (t: Throwable) {
                logError(t)
            }
        }

        current.forEach { (prefix, id) ->
            repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                Log.i(TAG, "updateMetadata loading ${repo.idPrefix}")
                val result = repo.load(id)
                val resultValue = result.getOrNull()
                val resultError = result.exceptionOrNull()
                if (resultValue != null) {
                    _metaResponse.postValue(Resource.Success(resultValue))
                    return@ioSafe
                } else if (resultError != null) {

                    /*Log.e(
                        TAG,
                        "updateMetadata error $id at ${repo.idPrefix} ${result.errorString}"
                    )*/
                    lastError = throwAbleToResource(resultError)
                }
            }
        }
        _metaResponse.postValue(lastError)
        setEpisodesDelta(0)
    }

    fun syncName(syncName: String): String? {
        // fix because of bad old data :pensive:
        val realName = when (syncName) {
            "MAL" -> malApi.idPrefix
            "Kitsu" -> kitsuApi.idPrefix
            "Simkl" -> simklApi.idPrefix
            "AniList" -> aniListApi.idPrefix
            else -> syncName
        }
        return repos.firstOrNull { it.idPrefix == realName }?.idPrefix
    }

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Thread-safe setSync with Mutex
    suspend fun setSync(syncName: String, syncId: String) {
        syncsMutex.withLock {
            syncs.clear()
            syncs[syncName] = syncId
            _syncsFlow.value = syncs.toMap()
        }
    }

    // Blocking version for compatibility
    fun setSyncBlocking(syncName: String, syncId: String) {
        syncs.clear()
        syncs[syncName] = syncId
        _syncsFlow.value = syncs.toMap()
    }

    // [SIMKL_DEFINITIVE_FIX][PHASE3] Thread-safe clear with Mutex - named differently to avoid conflict with ViewModel.clear()
    suspend fun clearAsync() {
        syncsMutex.withLock {
            syncs.clear()
            _syncsFlow.value = emptyMap()
        }
        _metaResponse.postValue(null)
        _currentSynced.postValue(getMissing())
        _userDataResponse.postValue(null)
    }

    // Non-suspend version that must be used from non-coroutine contexts (overrides ViewModel.clear() is not allowed)
    fun clearBlocking() {
        syncs.clear()
        _syncsFlow.value = emptyMap()
        _metaResponse.postValue(null)
        _currentSynced.postValue(getMissing())
        _userDataResponse.postValue(null)
    }

    fun updateMetaAndUser() {
        _userDataResponse.postValue(Resource.Loading())
        _metaResponse.postValue(Resource.Loading())

        Log.i(TAG, "updateMetaAndUser")
        updateMetadata()
        updateUserData()
    }
}