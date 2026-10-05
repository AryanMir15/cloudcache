package com.lagradost.cloudstream3.utils

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Persists the "linked source" pairing chosen on the result page: for a given
 * primary entry (apiName + url), the secondary provider entry whose episode
 * links are ALSO loaded during playback ([com.lagradost.cloudstream3.ui.player
 * .RepoLinkGenerator]). Only functional fields are stored — the paired entry's
 * metadata is never merged into the shown entry.
 */
object LinkedSourceManager {
    private const val TAG = "LinkedSource"
    private const val FOLDER = "linked_source_cache"

    data class LinkedSource(
        val primaryApiName: String,
        val primaryUrl: String,
        val secondaryApiName: String,
        val secondaryUrl: String,
        val secondaryName: String? = null,
    )

    /**
     * Composite of apiName + url instead of a hash so distinct entries can
     * never collide into the same key.
     */
    fun keyFor(primaryApiName: String, primaryUrl: String): String =
        "$primaryApiName|$primaryUrl"

    fun get(primaryApiName: String, primaryUrl: String): LinkedSource? {
        return try {
            CloudStreamApp.getKey(FOLDER, keyFor(primaryApiName, primaryUrl))
        } catch (t: Throwable) {
            Log.e(TAG, "get failed for $primaryApiName", t)
            null
        }
    }

    fun set(source: LinkedSource) {
        try {
            CloudStreamApp.setKey(
                FOLDER,
                keyFor(source.primaryApiName, source.primaryUrl),
                source
            )
            Log.i(
                TAG,
                "linked ${source.primaryApiName} -> " +
                        "${source.secondaryApiName} (${source.secondaryName})"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "set failed for ${source.primaryApiName}", t)
        }
    }

    fun remove(primaryApiName: String, primaryUrl: String) {
        try {
            CloudStreamApp.removeKey(FOLDER, keyFor(primaryApiName, primaryUrl))
            Log.i(TAG, "unlinked $primaryApiName")
        } catch (t: Throwable) {
            Log.e(TAG, "remove failed for $primaryApiName", t)
        }
    }
}
