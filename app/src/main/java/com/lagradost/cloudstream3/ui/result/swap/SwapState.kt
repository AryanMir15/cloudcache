package com.lagradost.cloudstream3.ui.result.swap

import com.lagradost.cloudstream3.ui.result.MetadataField

/**
 * Data class representing the state of a metadata swap operation
 * Stores original and current snapshots to enable proper undo functionality
 */
data class SwapState(
    val entryKey: String,  // unified key (URL or ID)
    val originalSnapshot: MetadataSnapshot,  // immutable after creation
    val currentSnapshot: MetadataSnapshot,
    val swappedFields: Set<MetadataField>,
    val swapTimestamp: Long,
    val isActive: Boolean
) {
    companion object {
        /**
         * Create a new SwapState from the original response
         * Both original and current snapshots are the same initially
         */
        fun create(
            entryKey: String,
            originalResponse: com.lagradost.cloudstream3.LoadResponse,
            fieldsToSwap: Set<MetadataField>
        ): SwapState {
            val snapshot = MetadataSnapshot.from(originalResponse)
            return SwapState(
                entryKey = entryKey,
                originalSnapshot = snapshot,
                currentSnapshot = snapshot,
                swappedFields = fieldsToSwap,
                swapTimestamp = System.currentTimeMillis(),
                isActive = true
            )
        }
    }
}
