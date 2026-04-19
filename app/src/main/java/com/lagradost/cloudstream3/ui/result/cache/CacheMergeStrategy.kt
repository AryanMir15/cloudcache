package com.lagradost.cloudstream3.ui.result.cache

/**
 * Enum representing different cache merge scenarios
 * Determines how fields should be preserved during cache updates
 */
enum class MergeScenario {
    /**
     * Provider data was updated (e.g., from API refresh)
     * Preserve swapped fields from cache, update non-swapped fields from provider
     */
    API_REFRESH,
    
    /**
     * Metadata swap was applied
     * Swapped fields from source, unswapped fields preserved from existing cache
     */
    SWAP_APPLY,
    
    /**
     * First time caching this entry
     * Use all fields from source
     */
    INITIAL_CACHE
}
