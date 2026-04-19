package com.lagradost.cloudstream3.ui.result.cache

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.result.swap.MetadataSnapshot
import com.lagradost.cloudstream3.ui.result.swap.SwapState

/**
 * Cache coordinator responsible for unified cache key resolution and intelligent cache merging
 * Ensures consistent cache access across the application and prevents data loss during updates
 */
object CacheCoordinator {
    
    private const val TAG = "CacheCoordinator"
    
    /**
     * Resolve a unified cache key from URL and/or ID
     * Ensures both URL-based and ID-based lookups find the same entry
     */
    fun resolveKey(url: String?, id: Int?): String {
        return when {
            url != null -> {
                // Try URL first
                val byUrl = getHeaderCached(url)
                if (byUrl != null) return url
                
                // Try to find by ID if provided
                if (id != null) {
                    val byId = getHeaderCached(id.toString())
                    if (byId != null) return url // Return URL for consistency
                }
                
                url
            }
            id != null -> id.toString()
            else -> throw IllegalArgumentException("Either url or id must be provided")
        }
    }
    
    /**
     * Get cached header by key with fallback resolution
     * Tries as URL, then as ID, then searches all entries
     */
    fun getHeaderCached(key: String): DownloadHeaderCached? {
        // Try as URL
        val byUrl = CloudStreamApp.getKey<DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, key)
        if (byUrl != null) {
            android.util.Log.d(TAG, "Found cache entry by URL: $key")
            return byUrl
        }
        
        // Try as ID
        val byId = CloudStreamApp.getKey<DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, key)
        if (byId != null) {
            android.util.Log.d(TAG, "Found cache entry by ID: $key")
            return byId
        }
        
        // Search all entries for matching URL or ID
        val allKeys = CloudStreamApp.getKeys(DOWNLOAD_HEADER_CACHE)
        val found = allKeys?.mapNotNull { 
            CloudStreamApp.getKey<DownloadHeaderCached>(it) 
        }?.find { 
            it.url == key || it.id.toString() == key 
        }
        
        if (found != null) {
            android.util.Log.d(TAG, "Found cache entry by search: $key")
        }
        
        return found
    }
    
    /**
     * Merge cache entry based on scenario and swap state
     * Critical rules:
     * - API_REFRESH: Provider values override non-swapped fields, swapped fields preserved from cache
     * - SWAP_APPLY: Swapped fields from source, unswapped fields preserved from existing or provider
     * - INITIAL_CACHE: Use all fields from source
     */
    fun mergeCacheEntry(
        existing: DownloadHeaderCached?,
        newData: LoadResponse,
        scenario: MergeScenario,
        swapState: SwapState?
    ): DownloadHeaderCached {
        android.util.Log.d(TAG, "mergeCacheEntry - scenario: $scenario, hasSwapState: ${swapState != null}")
        
        val snapshot = MetadataSnapshot.from(newData)
        
        return when (scenario) {
            MergeScenario.INITIAL_CACHE -> {
                // First time caching - use all fields from source
                android.util.Log.d(TAG, "INITIAL_CACHE - using all fields from source")
                DownloadHeaderCached(
                    apiName = newData.apiName,
                    url = newData.url,
                    id = newData.getId(),
                    type = newData.type,
                    name = newData.name,
                    poster = snapshot.posterUrl,
                    backgroundPosterUrl = snapshot.bannerUrl,
                    logoUrl = snapshot.logoUrl,
                    plot = snapshot.plot,
                    score = snapshot.score,
                    showStatus = snapshot.showStatus,
                    year = snapshot.year,
                    episodeCount = null, // Will be set separately
                    date = null,
                    actors = convertActorsToString(snapshot.actors),
                    tags = snapshot.tags,
                    cacheTime = System.currentTimeMillis(),
                    hasCustomPoster = false,
                    hasSwappedMetadata = false,
                    swappedFields = emptySet(),
                    originalPoster = null,
                    originalBanner = null,
                    originalLogo = null,
                    originalPlot = null,
                    originalActors = null,
                    originalScore = null,
                    originalYear = null,
                    originalShowStatus = null
                )
            }
            
            MergeScenario.API_REFRESH -> {
                // Provider data updated - preserve swapped fields from cache
                android.util.Log.d(TAG, "API_REFRESH - preserving swapped fields")
                val swappedFields = swapState?.swappedFields?.map { it.name } ?: emptySet()
                
                DownloadHeaderCached(
                    apiName = existing?.apiName ?: newData.apiName,
                    url = existing?.url ?: newData.url,
                    type = existing?.type ?: newData.type,
                    name = existing?.name ?: newData.name,
                    poster = if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER.name in swappedFields) {
                        existing?.poster // Preserve swapped
                    } else {
                        snapshot.posterUrl // Update from provider
                    },
                    backgroundPosterUrl = if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER.name in swappedFields) {
                        existing?.backgroundPosterUrl
                    } else {
                        snapshot.bannerUrl
                    },
                    logoUrl = if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO.name in swappedFields) {
                        existing?.logoUrl
                    } else {
                        snapshot.logoUrl
                    },
                    plot = if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT.name in swappedFields) {
                        existing?.plot
                    } else {
                        snapshot.plot
                    },
                    score = if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE.name in swappedFields) {
                        existing?.score
                    } else {
                        snapshot.score
                    },
                    showStatus = if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS.name in swappedFields) {
                        existing?.showStatus
                    } else {
                        snapshot.showStatus
                    },
                    year = if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR.name in swappedFields) {
                        existing?.year
                    } else {
                        snapshot.year
                    },
                    episodeCount = existing?.episodeCount,
                    date = existing?.date,
                    actors = if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS.name in swappedFields) {
                        existing?.actors
                    } else {
                        convertActorsToString(snapshot.actors)
                    },
                    tags = existing?.tags ?: snapshot.tags,
                    id = existing?.id ?: newData.getId(),
                    cacheTime = System.currentTimeMillis(),
                    hasCustomPoster = existing?.hasCustomPoster ?: false,
                    hasSwappedMetadata = existing?.hasSwappedMetadata ?: false,
                    swappedFields = existing?.swappedFields ?: emptySet(),
                    originalPoster = existing?.originalPoster,
                    originalBanner = existing?.originalBanner,
                    originalLogo = existing?.originalLogo,
                    originalPlot = existing?.originalPlot,
                    originalActors = existing?.originalActors,
                    originalScore = existing?.originalScore,
                    originalYear = existing?.originalYear,
                    originalShowStatus = existing?.originalShowStatus
                )
            }
            
            MergeScenario.SWAP_APPLY -> {
                // Swap applied - swapped fields from source, unswapped preserved from existing
                android.util.Log.d(TAG, "SWAP_APPLY - applying swapped fields")
                val swappedFields = swapState?.swappedFields?.map { it.name } ?: emptySet()
                
                DownloadHeaderCached(
                    apiName = existing?.apiName ?: newData.apiName,
                    url = existing?.url ?: newData.url,
                    type = existing?.type ?: newData.type,
                    name = existing?.name ?: newData.name,
                    poster = if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER.name in swappedFields) {
                        snapshot.posterUrl // Use swapped value
                    } else {
                        existing?.poster ?: snapshot.posterUrl // Preserve existing or use source
                    },
                    backgroundPosterUrl = if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER.name in swappedFields) {
                        snapshot.bannerUrl
                    } else {
                        existing?.backgroundPosterUrl ?: snapshot.bannerUrl
                    },
                    logoUrl = if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO.name in swappedFields) {
                        snapshot.logoUrl
                    } else {
                        existing?.logoUrl ?: snapshot.logoUrl
                    },
                    plot = if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT.name in swappedFields) {
                        snapshot.plot
                    } else {
                        existing?.plot ?: snapshot.plot
                    },
                    score = if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE.name in swappedFields) {
                        snapshot.score
                    } else {
                        existing?.score ?: snapshot.score
                    },
                    showStatus = if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS.name in swappedFields) {
                        snapshot.showStatus
                    } else {
                        existing?.showStatus ?: snapshot.showStatus
                    },
                    year = if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR.name in swappedFields) {
                        snapshot.year
                    } else {
                        existing?.year ?: snapshot.year
                    },
                    episodeCount = existing?.episodeCount,
                    date = existing?.date,
                    actors = if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS.name in swappedFields) {
                        convertActorsToString(snapshot.actors)
                    } else {
                        existing?.actors ?: convertActorsToString(snapshot.actors)
                    },
                    tags = existing?.tags ?: snapshot.tags,
                    id = existing?.id ?: newData.getId(),
                    cacheTime = System.currentTimeMillis(),
                    hasCustomPoster = true,
                    hasSwappedMetadata = true,
                    swappedFields = swappedFields.toSet(),
                    originalPoster = if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER.name in swappedFields) {
                        existing?.poster
                    } else {
                        null
                    },
                    originalBanner = if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER.name in swappedFields) {
                        existing?.backgroundPosterUrl
                    } else {
                        null
                    },
                    originalLogo = if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO.name in swappedFields) {
                        existing?.logoUrl
                    } else {
                        null
                    },
                    originalPlot = if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT.name in swappedFields) {
                        existing?.plot
                    } else {
                        null
                    },
                    originalActors = if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS.name in swappedFields) {
                        existing?.actors
                    } else {
                        null
                    },
                    originalScore = if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE.name in swappedFields) {
                        existing?.score
                    } else {
                        null
                    },
                    originalYear = if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR.name in swappedFields) {
                        existing?.year
                    } else {
                        null
                    },
                    originalShowStatus = if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS.name in swappedFields) {
                        existing?.showStatus
                    } else {
                        null
                    }
                )
            }
        }
    }
    
    /**
     * Convert List<ActorData> to List<String> for cache storage
     */
    private fun convertActorsToString(actors: List<com.lagradost.cloudstream3.ActorData>?): List<String>? {
        if (actors.isNullOrEmpty()) return null
        return actors.map { actorData ->
            "${actorData.actor.name}|${actorData.actor.image ?: ""}|${actorData.role?.name ?: ""}|${actorData.roleString ?: ""}|${actorData.voiceActor?.name ?: ""}|${actorData.voiceActor?.image ?: ""}"
        }
    }
}
