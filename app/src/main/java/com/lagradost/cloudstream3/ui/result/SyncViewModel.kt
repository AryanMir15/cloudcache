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

    private val _successMessage: MutableLiveData<String?> = MutableLiveData(null)
    val successMessage: LiveData<String?> = _successMessage
    
    fun clearSuccessMessage() {
        _successMessage.postValue(null)
    }
    
    private val _selectedProvider: MutableLiveData<String?> = MutableLiveData(null)
    val selectedProvider: LiveData<String?> = _selectedProvider
    
    fun setSelectedProvider(provider: String?) {
        _selectedProvider.postValue(provider)
    }
    
    private val _isSyncing: MutableLiveData<Boolean> = MutableLiveData(false)
    val isSyncing: LiveData<Boolean> = _isSyncing

    // prefix, id
    // [SIMKL_DEFINITIVE_FIX][PHASE3] Mutex-protected syncs map for thread safety
    private val syncsMutex = Mutex()
    private val syncs = mutableMapOf<String, String>()
    
    // [RACE_CONDITION_FIX] Track in-flight requests to prevent overlapping calls
    private val isFetchingUserData = AtomicBoolean(false)
    private var lastRequestedSyncs: Map<String, String> = emptyMap()
    // Queued refresh request: set when updateUserData is called while a fetch is in flight
    private val pendingUserDataRefresh = AtomicBoolean(false)

    
    // StateFlow for reactive sync updates
    private val _syncsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val syncsFlow: StateFlow<Map<String, String>> = _syncsFlow

    // Manual entry search state
    private val _searchResults: MutableLiveData<List<SyncAPI.SyncSearchResult>> = MutableLiveData()
    val searchResults: LiveData<List<SyncAPI.SyncSearchResult>> = _searchResults

    private val _isSearching: MutableLiveData<Boolean> = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    fun getSyncs(): Map<String, String> {
        return syncs.toMap()
    }

    private val _currentSynced: MutableLiveData<List<CurrentSynced>> =
        MutableLiveData(getMissing())

    // pair of name idPrefix isSynced
    val synced: LiveData<List<CurrentSynced>> = _currentSynced

    // Track which providers have valid sync status (not NONE)
    private val _providersWithValidStatus = MutableLiveData<Set<String>>(emptySet())
    val providersWithValidStatus: LiveData<Set<String>> = _providersWithValidStatus

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
        } else {
            Log.w(TAG, "setEpisodesDelta - skipped, user data not loaded (${user?.javaClass?.simpleName ?: "null"}), triggering refresh")
            updateUserData()
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
                    // Create new SyncStatus to ensure LiveData detects the change
                    SyncAPI.SyncStatus(
                        status = currentUser?.status ?: SyncWatchType.NONE,
                        score = currentUser?.score,
                        watchedEpisodes = episodes,
                        isFavorite = currentUser?.isFavorite,
                        maxEpisodes = currentUser?.maxEpisodes
                    )
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        } else {
            Log.w(TAG, "setEpisodes - skipped, user data not loaded (${user?.javaClass?.simpleName ?: "null"}), triggering refresh")
            updateUserData()
        }
    }

    fun setDates(startDate: Long?, endDate: Long?) {
        Log.i(TAG, "setDates = $startDate to $endDate")
        val user = userData.value
        if (user is Resource.Success) {
            val currentUser = user.value
            val updatedUser = when (currentUser) {
                is com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus -> {
                    com.lagradost.cloudstream3.syncproviders.providers.SimklApi.SimklSyncStatus(
                        status = currentUser.status,
                        score = currentUser.score,
                        oldScore = currentUser.oldScore,
                        watchedEpisodes = currentUser.watchedEpisodes,
                        episodeConstructor = currentUser.episodeConstructor,
                        isFavorite = currentUser.isFavorite,
                        maxEpisodes = currentUser.maxEpisodes,
                        startDate = startDate,
                        endDate = endDate,
                        oldEpisodes = currentUser.oldEpisodes,
                        oldStatus = currentUser.oldStatus
                    )
                }
                else -> {
                    SyncAPI.SyncStatus(
                        status = currentUser?.status ?: SyncWatchType.NONE,
                        score = currentUser?.score,
                        watchedEpisodes = currentUser?.watchedEpisodes,
                        isFavorite = currentUser?.isFavorite,
                        maxEpisodes = currentUser?.maxEpisodes,
                        startDate = startDate,
                        endDate = endDate
                    )
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        } else {
            Log.w(TAG, "setDates - skipped, user data not loaded (${user?.javaClass?.simpleName ?: "null"}), triggering refresh")
            updateUserData()
        }
    }

    fun setScore(score: Score?): Boolean {
        Log.i(TAG, "setScore = $score")
        val user = userData.value
        if (user is Resource.Success) {
            val currentUser = user.value
            
            // No-op protection: skip if score hasn't changed
            if (currentUser.score == score) {
                Log.d(TAG, "setScore - no-op, score unchanged")
                return false
            }
            
            // Auto-normalization: set status to PLANNING if entry not in list and score > 0
            if (currentUser.status == SyncWatchType.NONE && score != null && score.toInt() > 0) {
                Log.i(TAG, "SYNCVM auto-added entry to PLANNING before score update")
                setStatus(SyncWatchType.PLANTOWATCH.internalId)
            }
            
            // Create immutable copy with new score to ensure UI updates
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
                    // Create new SyncStatus to ensure LiveData detects the change
                    SyncAPI.SyncStatus(
                        status = currentUser?.status ?: SyncWatchType.NONE,
                        score = score,
                        watchedEpisodes = currentUser?.watchedEpisodes,
                        isFavorite = currentUser?.isFavorite,
                        maxEpisodes = currentUser?.maxEpisodes
                    )
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
            return true
        } else {
            Log.w(TAG, "setScore - skipped, user data not loaded (${user?.javaClass?.simpleName ?: "null"}), triggering refresh")
            updateUserData()
        }
        return false
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
                    // Create new SyncStatus to ensure LiveData detects the change
                    SyncAPI.SyncStatus(
                        status = newStatus,
                        score = currentUser?.score,
                        watchedEpisodes = currentUser?.watchedEpisodes,
                        isFavorite = currentUser?.isFavorite,
                        maxEpisodes = currentUser?.maxEpisodes
                    )
                }
            }
            _userDataResponse.postValue(Resource.Success(updatedUser))
        } else {
            Log.w(TAG, "setStatus - skipped, user data not loaded (${user?.javaClass?.simpleName ?: "null"}), triggering refresh")
            updateUserData()
        }
    }

    fun publishUserData() = ioSafe {
        Log.i(TAG, "publishUserData")
        _isSyncing.postValue(true)
        _userDataResponse.postValue(Resource.Loading())
        
        try {
            val user = userData.value
            val successfulProviders = mutableListOf<String>()
            val failedProviders = mutableListOf<String>()
            val selected = selectedProvider.value
            
            if (user is Resource.Success) {
                syncsMutex.withLock { syncs.toMap() }.forEach { (prefix, id) ->
                    // If a specific provider is selected, only sync to that one
                    if (selected != null && prefix != selected.lowercase()) {
                        Log.i(TAG, "Skipping $prefix - not selected (selected: $selected)")
                        return@forEach
                    }
                    
                    val repo = repos.firstOrNull { it.idPrefix == prefix }
                    if (repo != null) {
                        try {
                            // Optimization: if specific provider is selected, skip status check and sync directly
                            if (selected != null) {
                                // Specific provider selected - sync directly without status check
                                val updateResult = repo.updateStatus(id, user.value)
                                if (updateResult.isSuccess && updateResult.getOrNull() == true) {
                                    successfulProviders.add(prefix.uppercase())
                                    Log.i(TAG, "Synced to $prefix (direct sync, no status check)")
                                } else {
                                    failedProviders.add(prefix.uppercase())
                                    Log.e(TAG, "Failed to sync to $prefix: ${updateResult.exceptionOrNull()?.message ?: "unknown error"}")
                                }
                            } else {
                                // All providers selected - check if provider has account before syncing
                                val statusResult = repo.status(id)
                                if (statusResult?.isSuccess == true && statusResult.getOrNull() != null) {
                                    val updateResult = repo.updateStatus(id, user.value)
                                    if (updateResult.isSuccess && updateResult.getOrNull() == true) {
                                        successfulProviders.add(prefix.uppercase())
                                    } else {
                                        failedProviders.add(prefix.uppercase())
                                        Log.e(TAG, "Failed to sync to $prefix: ${updateResult.exceptionOrNull()?.message ?: "unknown error"}")
                                    }
                                } else {
                                    Log.i(TAG, "Skipping $prefix - no account or null status")
                                }
                            }
                        } catch (e: Exception) {
                            failedProviders.add(prefix.uppercase())
                            Log.e(TAG, "Failed to sync to $prefix", e)
                        }
                    }
                }
            }
            updateUserData()
            
            // Show success message only for providers that succeeded
            if (successfulProviders.isNotEmpty()) {
                val animeName = (metadata.value as? Resource.Success)?.value?.title ?: "anime"
                val syncProviders = successfulProviders.joinToString(", ")
                _successMessage.postValue("Synced to $syncProviders for $animeName")
            } else if (failedProviders.isNotEmpty()) {
                val animeName = (metadata.value as? Resource.Success)?.value?.title ?: "anime"
                val syncProviders = failedProviders.joinToString(", ")
                _successMessage.postValue("Failed to sync to $syncProviders for $animeName")
            }
        } finally {
            _isSyncing.postValue(false)
        }
    }

    fun fetchProviderStatus(provider: String) = ioSafe {
        Log.i(TAG, "fetchProviderStatus for provider: $provider")
        val providerId = syncs[provider.lowercase()]
        if (providerId != null) {
            val repo = repos.firstOrNull { it.idPrefix == provider.lowercase() }
            if (repo != null) {
                try {
                    val statusResult = repo.status(providerId)
                    if (statusResult?.isSuccess == true) {
                        val status = statusResult.getOrNull()
                        if (status != null) {
                            _userDataResponse.postValue(Resource.Success(status))
                            Log.i(TAG, "Fetched status for $provider: $status")
                        } else {
                            // Entry not synced with this provider
                            _userDataResponse.postValue(Resource.Success(createEmptyStatus()))
                            Log.i(TAG, "Entry not synced with $provider")
                        }
                    } else {
                        val error = statusResult?.exceptionOrNull()?.message ?: "Failed to fetch status"
                        _userDataResponse.postValue(Resource.Failure(false, error))
                        Log.e(TAG, "Failed to fetch status for $provider: $error")
                    }
                } catch (e: Exception) {
                    _userDataResponse.postValue(Resource.Failure(false, e.message ?: "Error fetching status"))
                    Log.e(TAG, "Error fetching status for $provider", e)
                }
            } else {
                Log.e(TAG, "Repo not found for provider: $provider")
            }
        } else {
            // No ID for this provider - entry not synced
            _userDataResponse.postValue(Resource.Success(createEmptyStatus()))
            Log.i(TAG, "No ID found for provider $provider - entry not synced")
        }
    }

    private fun createEmptyStatus(): SyncAPI.AbstractSyncStatus {
        // Return a default/empty status object using SyncStatus
        return SyncAPI.SyncStatus(
            status = SyncWatchType.NONE,
            score = null,
            watchedEpisodes = 0,
            isFavorite = null,
            maxEpisodes = 0
        )
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
            syncsMutex.withLock { syncs.toMap() }.amap { (prefix, id) ->
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
        
        // Try to acquire the flag - if already true, another call is in progress
        if (!isFetchingUserData.compareAndSet(false, true)) {
            // If same data is being fetched, skip entirely
            if (currentSyncs == lastRequestedSyncs) {
                Log.i(TAG, "updateUserData - SKIPPED: identical request already in progress")
            } else {
                // Different data was requested: queue it so it runs after the in-flight request
                pendingUserDataRefresh.set(true)
                Log.i(TAG, "updateUserData - QUEUED: refresh will run after current request completes")
            }
            return
        }
        
        lastRequestedSyncs = currentSyncs
        
        viewModelScope.launch {
            try {
                _userDataResponse.postValue(Resource.Loading())

            var triedApis = 0
            var anySuccess = false
            var status: SyncAPI.AbstractSyncStatus? = null
            val providersWithValidStatus = mutableSetOf<String>()
            
            currentSyncs.forEach { (prefix, id) ->
                // If a specific provider is selected, only fetch that provider's status
                val selected = selectedProvider.value
                if (selected != null && prefix != selected.lowercase()) {
                    return@forEach
                }
                triedApis++
                Log.i(TAG, "updateUserData - trying $prefix with id $id")
                val repo = repos.firstOrNull { it.idPrefix == prefix }
                Log.i(TAG, "updateUserData - repo for $prefix: ${repo != null}")
                val result = repo?.status(id)
                Log.i(TAG, "updateUserData - status result for $prefix: isSuccess=${result?.isSuccess}, isFailure=${result?.isFailure}")
                if (result?.isSuccess == true) {
                    anySuccess = true
                    val statusValue = result.getOrNull()
                    if (statusValue != null) {
                        // Check if status is valid (not NONE or has watched episodes or is favorite)
                        val isValid = statusValue.status != SyncWatchType.NONE && 
                                      statusValue.status != null &&
                                      ((statusValue.watchedEpisodes ?: 0) > 0 || statusValue.isFavorite == true)
                        if (isValid) {
                            providersWithValidStatus.add(prefix)
                        }
                        status = statusValue
                        Log.i(TAG, "updateUserData - SUCCESS for $prefix with non-null status, isValid: $isValid")
                    } else {
                        Log.i(TAG, "updateUserData - SUCCESS for $prefix with null status - skipping")
                    }
                }
            }
            
            // Update providers with valid status
            _providersWithValidStatus.postValue(providersWithValidStatus)
            Log.i(TAG, "updateUserData - providers with valid status: $providersWithValidStatus")

            Log.i(TAG, "updateUserData - tried $triedApis APIs, anySuccess: $anySuccess, status is null: ${status == null}")

            // Post Success with EmptySyncStatus if APIs succeeded but returned null status
            // This prevents infinite retry loop while signaling that the work is complete
            if (anySuccess && status == null) {
                _userDataResponse.postValue(Resource.Success(com.lagradost.cloudstream3.syncproviders.SyncAPI.EmptySyncStatus))
            } else if (status != null) {
                _userDataResponse.postValue(Resource.Success(status))
            } else {
                _userDataResponse.postValue(Resource.Failure(false, "No data"))
            }
            } finally {
                // [RACE_CONDITION_FIX] Always release the flag
                isFetchingUserData.set(false)
                Log.i(TAG, "updateUserData - request completed, flag released")
                // Run a queued refresh if one was requested while this fetch was in flight
                if (pendingUserDataRefresh.compareAndSet(true, false)) {
                    Log.i(TAG, "updateUserData - running queued refresh")
                    updateUserData()
                }
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
        // Reset fetch flag so in-flight requests don't block future calls
        isFetchingUserData.set(false)
        _metaResponse.postValue(null)
        _currentSynced.postValue(getMissing())
        _userDataResponse.postValue(null)
    }

    // Non-suspend version that must be used from non-coroutine contexts (overrides ViewModel.clear() is not allowed)
    fun clearBlocking() {
        syncs.clear()
        _syncsFlow.value = emptyMap()
        // Reset fetch flag so in-flight requests don't block future calls
        isFetchingUserData.set(false)
        _metaResponse.postValue(null)
        _currentSynced.postValue(getMissing())
        _userDataResponse.postValue(null)
    }

    /**
     * Search a specific tracker provider for entries matching the query.
     * Currently only AniList is supported.
     */
    fun searchTracker(query: String, providerPrefix: String = aniListApi.idPrefix) = ioSafe {
        Log.i(TAG, "searchTracker - query: $query, provider: $providerPrefix")
        _isSearching.postValue(true)
        _searchResults.postValue(emptyList())

        try {
            val repo = repos.firstOrNull { it.idPrefix == providerPrefix }
            if (repo == null) {
                Log.e(TAG, "searchTracker - repo not found for provider: $providerPrefix")
                _isSearching.postValue(false)
                return@ioSafe
            }

            val result = repo.search(query)
            if (result.isSuccess) {
                val results = result.getOrNull() ?: emptyList()
                Log.i(TAG, "searchTracker - found ${results.size} results")
                _searchResults.postValue(results)
            } else {
                Log.e(TAG, "searchTracker - search failed", result.exceptionOrNull())
                _searchResults.postValue(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchTracker - error", e)
            _searchResults.postValue(emptyList())
        } finally {
            _isSearching.postValue(false)
        }
    }

    /**
     * Remove sync entry for a specific provider.
     */
    fun removeSyncForProvider(prefix: String) = ioSafe {
        Log.i(TAG, "removeSyncForProvider - prefix: $prefix")
        syncsMutex.withLock {
            syncs.remove(prefix)
            _syncsFlow.value = syncs.toMap()
        }
        updateSynced()
        // Reset metadata and user data since the entry was removed
        _metaResponse.postValue(null)
        _userDataResponse.postValue(null)
    }

    /**
     * Replace sync entry for a specific provider with a new ID.
     * Then refreshes metadata and user data.
     */
    fun replaceSyncEntry(prefix: String, newId: String) = ioSafe {
        Log.i(TAG, "replaceSyncEntry - prefix: $prefix, newId: $newId")
        syncsMutex.withLock {
            syncs[prefix] = newId
            _syncsFlow.value = syncs.toMap()
        }
        updateSynced()
        updateMetaAndUser()
    }

    /**
     * Get the current syncs map as a mutable copy for external persistence updates.
     */
    fun getCurrentSyncData(): Map<String, String> {
        return syncs.toMap()
    }

    fun updateMetaAndUser() {
        _userDataResponse.postValue(Resource.Loading())
        _metaResponse.postValue(Resource.Loading())

        Log.i(TAG, "updateMetaAndUser")
        updateMetadata()
        updateUserData()
    }
}