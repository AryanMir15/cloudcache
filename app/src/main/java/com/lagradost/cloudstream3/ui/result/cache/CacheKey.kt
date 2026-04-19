package com.lagradost.cloudstream3.ui.result.cache

/**
 * Sealed class representing different types of cache keys
 * Used for unified cache key resolution across the application
 */
sealed class CacheKey {
    data class ByUrl(val url: String) : CacheKey()
    data class ById(val id: Int) : CacheKey()
    
    /**
     * Convert to string representation for storage
     */
    override fun toString(): String {
        return when (this) {
            is ByUrl -> url
            is ById -> id.toString()
        }
    }
    
    companion object {
        /**
         * Parse a string into a CacheKey
         * Attempts to determine if it's an ID or URL
         */
        fun fromString(key: String): CacheKey {
            return if (key.toIntOrNull() != null) {
                ById(key.toInt())
            } else {
                ByUrl(key)
            }
        }
    }
}
