package com.lagradost.cloudstream3.ui.result.swap

import android.content.Context
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.result.MetadataField

/**
 * MetadataSwapManager manages swap state with in-memory storage
 * Maintains swap state machine: NONE → SWAPPED → UNDO_PENDING
 * Stores original snapshot once (immutable) and current snapshot (mutable)
 */
object MetadataSwapManager {
    private const val TAG = "MetadataSwapManager"
    
    // In-memory storage for swap states
    private val swapStates = mutableMapOf<String, SwapState>()
    
    /**
     * Record a swap operation
     * On first swap, stores original snapshot (never modified after)
     * On subsequent swaps, keeps existing original, applies to current
     */
    suspend fun recordSwap(
        context: Context,
        entryKey: String,
        originalResponse: LoadResponse,
        swapSourceResponse: LoadResponse,
        fieldsToSwap: Set<MetadataField>
    ) {
        android.util.Log.d(TAG, "recordSwap - entryKey: $entryKey, fieldsToSwap: $fieldsToSwap")
        
        val existingState = swapStates[entryKey]
        
        val newState = if (existingState == null) {
            // First swap - store original snapshot
            android.util.Log.d(TAG, "First swap - creating new SwapState with original snapshot")
            SwapState.create(entryKey, originalResponse, fieldsToSwap)
        } else {
            // Subsequent swap - keep existing originalSnapshot, update current
            android.util.Log.d(TAG, "Subsequent swap - updating current snapshot")
            val sourceSnapshot = MetadataSnapshot.from(swapSourceResponse)
            existingState.copy(
                currentSnapshot = sourceSnapshot,
                swappedFields = fieldsToSwap,
                swapTimestamp = System.currentTimeMillis(),
                isActive = true
            )
        }
        
        swapStates[entryKey] = newState
        android.util.Log.d(TAG, "Swap state saved for entryKey: $entryKey")
    }
    
    /**
     * Apply swap to current snapshot
     * Used when user selects new metadata to swap
     */
    suspend fun applySwap(
        context: Context,
        entryKey: String,
        swapSourceResponse: LoadResponse,
        fieldsToSwap: Set<MetadataField>
    ) {
        android.util.Log.d(TAG, "applySwap - entryKey: $entryKey, fieldsToSwap: $fieldsToSwap")
        
        val existingState = swapStates[entryKey]
            ?: throw IllegalStateException("No existing swap state for entryKey: $entryKey")
        
        val sourceSnapshot = MetadataSnapshot.from(swapSourceResponse)
        val newState = existingState.copy(
            currentSnapshot = sourceSnapshot,
            swappedFields = fieldsToSwap,
            swapTimestamp = System.currentTimeMillis(),
            isActive = true
        )
        
        swapStates[entryKey] = newState
        android.util.Log.d(TAG, "Swap applied for entryKey: $entryKey")
    }
    
    /**
     * Undo swap - restores original snapshot to current, clears swap flags
     */
    suspend fun undoSwap(context: Context, entryKey: String) {
        android.util.Log.d(TAG, "undoSwap - entryKey: $entryKey")
        
        val existingState = swapStates[entryKey]
            ?: throw IllegalStateException("No existing swap state for entryKey: $entryKey")
        
        val newState = existingState.copy(
            currentSnapshot = existingState.originalSnapshot, // Restore original
            swappedFields = emptySet(),
            isActive = false
        )
        
        swapStates[entryKey] = newState
        android.util.Log.d(TAG, "Swap undone for entryKey: $entryKey")
    }
    
    /**
     * Get current metadata for display (swap-aware)
     * Returns currentSnapshot if swapped, null if not swapped
     */
    suspend fun getCurrentMetadata(context: Context, entryKey: String): MetadataSnapshot? {
        val state = swapStates[entryKey]
        return if (state?.isActive == true) {
            android.util.Log.d(TAG, "getCurrentMetadata - returning current snapshot (swapped)")
            state.currentSnapshot
        } else {
            android.util.Log.d(TAG, "getCurrentMetadata - no active swap, returning null")
            null
        }
    }
    
    /**
     * Check if entry has active swap
     */
    suspend fun isSwapped(context: Context, entryKey: String): Boolean {
        val state = swapStates[entryKey]
        return state?.isActive == true
    }
    
    /**
     * Get swap state for entry
     */
    suspend fun getSwapState(context: Context, entryKey: String): SwapState? {
        return swapStates[entryKey]
    }
    
    /**
     * Clear swap state for entry
     */
    suspend fun clearSwapState(context: Context, entryKey: String) {
        android.util.Log.d(TAG, "clearSwapState - entryKey: $entryKey")
        swapStates.remove(entryKey)
    }
    
    /**
     * Get all swap states (for debugging/migration)
     */
    suspend fun getAllSwapStates(context: Context): Map<String, SwapState> {
        android.util.Log.d(TAG, "Loaded ${swapStates.size} swap states")
        return swapStates.toMap()
    }
}
