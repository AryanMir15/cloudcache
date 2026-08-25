package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentCacheManagementBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class CacheManagementFragment : BaseFragment<FragmentCacheManagementBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentCacheManagementBinding::inflate)
) {
    private val cacheEntries = mutableListOf<CacheEntry>()
    private lateinit var adapter: CacheEntryAdapter

    override fun onBindingCreated(binding: FragmentCacheManagementBinding) {
        super.onBindingCreated(binding)

        adapter = CacheEntryAdapter(cacheEntries) { entry ->
            deleteCacheEntry(entry)
        }

        binding.cacheRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CacheManagementFragment.adapter
        }

        binding.clearAllCacheButton.setOnClickListener {
            clearAllCache()
        }

        loadCacheEntries()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    private fun loadCacheEntries() {
        try {
            val keys = com.lagradost.cloudstream3.CloudStreamApp.getKeys(DOWNLOAD_EPISODE_CACHE)
            cacheEntries.clear()

            var totalSize = 0L

            // Group episodes by parentId to show anime-based entries
            val episodesByParentId = mutableMapOf<Int, MutableList<Pair<String, DownloadObjects.DownloadEpisodeCached>>>()

            keys?.forEach { key ->
                // Keys from getKeys already include cache name prefix, so use getKey without cache name parameter
                val cachedData = com.lagradost.cloudstream3.CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(key)
                cachedData?.let {
                    val parentId = it.parentId
                    if (parentId != 0) {
                        episodesByParentId.getOrPut(parentId) { mutableListOf() }.add(key to it)
                    }
                }
            }

            // Convert grouped episodes to cache entries (one per anime)
            episodesByParentId.forEach { (parentId, episodes) ->
                // Get anime name from header cache (keyed by id or url)
                val animeName = com.lagradost.cloudstream3.CloudStreamApp.getKeys(
                    com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
                )?.mapNotNull {
                    com.lagradost.cloudstream3.CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(it)
                }?.firstOrNull { it.id == parentId }?.name
                    ?: episodes.firstOrNull()?.second?.name?.let { name ->
                        // Fallback: try to extract anime name from episode name
                        name.replace(Regex("Episode \\d+.*"), "").trim().ifEmpty { name }
                    } ?: "Unknown"

                val episodeCount = episodes.size
                val size = episodes.sumOf { (key, _) -> calculateCacheSize(key) }
                totalSize += size

                cacheEntries.add(
                    CacheEntry(
                        key = parentId.toString(),
                        name = "$animeName ($episodeCount episodes)",
                        size = size,
                        hasSwappedMetadata = false
                    )
                )
            }

            binding?.totalCacheSizeText?.text = formatSize(totalSize)
            binding?.cacheCountText?.text = "${cacheEntries.size} anime"
            adapter.notifyDataSetChanged()

        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun calculateCacheSize(entryKey: String): Long {
        // Real size: the JSON string stored for this key
        return try {
            requireContext().getSharedPrefs().getString(entryKey, null)?.length?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Removes every header-cache entry whose id matches, regardless of whether
     * it is stored under the id key or the url key.
     */
    private fun deleteMatchingHeaders(parentId: Int) {
        com.lagradost.cloudstream3.CloudStreamApp.getKeys(
            com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
        )?.forEach { key ->
            val header = com.lagradost.cloudstream3.CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(key)
            if (header?.id == parentId) {
                com.lagradost.cloudstream3.CloudStreamApp.removeKey(key)
            }
        }
    }

    private fun deleteCacheEntry(entry: CacheEntry) {
        try {
            // entry.key is now the parentId, delete all episodes with that parentId
            val parentId = entry.key.toIntOrNull() ?: return
            val keys = com.lagradost.cloudstream3.CloudStreamApp.getKeys(DOWNLOAD_EPISODE_CACHE)
            
            keys?.forEach { key ->
                val cachedData = com.lagradost.cloudstream3.CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(key)
                if (cachedData?.parentId == parentId) {
                    com.lagradost.cloudstream3.CloudStreamApp.removeKey(key)
                }
            }

            // The header is what lets the app silently re-cache this show on the
            // next open, so it must go too
            deleteMatchingHeaders(parentId)
            
            loadCacheEntries()
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun clearAllCache() {
        try {
            android.util.Log.d("CachePerformance", "=== CACHEMANAGEMENTFRAGMENT: CLEARING ALL CACHE ===")
            com.lagradost.cloudstream3.CloudStreamApp.removeKeys(DOWNLOAD_EPISODE_CACHE)
            android.util.Log.d("CachePerformance", "Cleared all DOWNLOAD_EPISODE_CACHE entries")
            
            // The headers must go too, otherwise shows silently re-cache on next open
            val removedHeaders = com.lagradost.cloudstream3.CloudStreamApp.removeKeys(
                com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
            )
            android.util.Log.d("CachePerformance", "Cleared $removedHeaders DOWNLOAD_HEADER_CACHE entries")

            // Also clear all parent indices.
            // Index keys are written as "${EPISODE_PARENT_INDEX}_${parentId}" (underscore
            // suffix), so the folder-style getKeys below would match nothing.
            val indexKeys = try {
                requireContext().getSharedPrefs().all.keys
                    .filter { it.startsWith(com.lagradost.cloudstream3.utils.EPISODE_PARENT_INDEX + "_") }
                    .toList()
            } catch (e: Exception) {
                emptyList()
            }
            android.util.Log.d("CachePerformance", "Found ${indexKeys.size} parent index keys to clear")
            indexKeys.forEach { key ->
                com.lagradost.cloudstream3.CloudStreamApp.removeKey(key)
                android.util.Log.d("CachePerformance", "Cleared parent index key: $key")
            }
            android.util.Log.d("CachePerformance", "=== CACHEMANAGEMENTFRAGMENT: CACHE CLEARING COMPLETE ===")
            loadCacheEntries()
        } catch (e: Exception) {
            android.util.Log.e("CachePerformance", "Error clearing all cache: ${e.message}", e)
            logError(e)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    data class CacheEntry(
        val key: String,
        val name: String,
        val size: Long,
        val hasSwappedMetadata: Boolean
    )

    class CacheEntryAdapter(
        private val entries: List<CacheEntry>,
        private val onDelete: (CacheEntry) -> Unit
    ) : RecyclerView.Adapter<CacheEntryAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cache_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.itemView.findViewById<android.widget.TextView>(R.id.cache_entry_name)?.text = entry.name
            holder.itemView.findViewById<android.widget.TextView>(R.id.cache_entry_size)?.text = formatSize(entry.size)
            holder.itemView.findViewById<android.widget.TextView>(R.id.cache_entry_swapped)?.text = 
                if (entry.hasSwappedMetadata) "Swapped" else "Normal"
            holder.itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.delete_cache_entry_button)?.setOnClickListener {
                onDelete(entry)
            }
        }

        override fun getItemCount() = entries.size

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}
