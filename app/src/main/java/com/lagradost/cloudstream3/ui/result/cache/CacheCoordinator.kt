package com.lagradost.cloudstream3.ui.result.cache

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached

/**
 * Cache coordinator responsible for unified cache key resolution
 * Ensures consistent cache access across the application
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
}