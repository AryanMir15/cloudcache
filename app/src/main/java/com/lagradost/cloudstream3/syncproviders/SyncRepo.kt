package com.lagradost.cloudstream3.syncproviders

/** Stateless safe abstraction of SyncAPI */
class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName = api.syncIdName
    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) {
            api.requireLibraryRefresh = value
        }

    suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> =
        runCatching {
            val status = api.updateStatus(freshAuth() ?: return@runCatching false, id, newStatus)
            requireLibraryRefresh = true
            status
        }

    suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> = runCatching {
        api.status(freshAuth(), id)
    }

    suspend fun load(id: String): Result<SyncAPI.SyncResult?> = runCatching {
        api.load(freshAuth(), id)
    }

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = runCatching {
        api.library(freshAuth())
    }

    suspend fun search(query: String): Result<List<SyncAPI.SyncSearchResult>?> = runCatching {
        // Try freshAuth first, but fall back to raw authData if refresh fails.
        // Some providers (AniList) support unauthenticated search, so a null auth is OK.
        val auth = try { freshAuth() } catch (_: Exception) { authData() }
        api.search(auth, query)
    }
}
