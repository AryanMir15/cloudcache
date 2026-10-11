package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.ui.player.extractEpisodeNumber
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DownloadScanner {
    private const val TAG = "DownloadScanner"
    private val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v")

    private fun getDownloadPath(context: Context): String? {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val path = settingsManager.getString(context.getString(R.string.download_path_key), null)
        Log.d(TAG, "Download path from settings: $path")
        return path
    }

    fun clearLibraryCache(context: Context, headerId: Int) {
        try {
            // Get all episode keys for this header first
            val episodeKeys = com.lagradost.cloudstream3.CloudStreamApp.getKeys(DOWNLOAD_EPISODE_CACHE)
            val episodesToDelete = episodeKeys?.mapNotNull { key ->
                com.lagradost.cloudstream3.CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(DOWNLOAD_EPISODE_CACHE, key)
            }?.filter { it.parentId == headerId } ?: emptyList()
            
            val episodeIds = episodesToDelete.map { it.id }
            
            android.util.Log.d("CachePerformance", "=== DOWNLOADSCANNER: CLEARING LIBRARY CACHE ===")
            android.util.Log.d("CachePerformance", "HeaderId: $headerId")
            android.util.Log.d("CachePerformance", "Episodes to delete: ${episodeIds.size}")
            android.util.Log.d("CachePerformance", "Episode IDs: ${episodeIds.take(5)}${if (episodeIds.size > 5) "..." else ""}")
            
            // Clear episode cache
            episodesToDelete.forEach { ep ->
                com.lagradost.cloudstream3.CloudStreamApp.removeKey(DOWNLOAD_EPISODE_CACHE, ep.id.toString())
            }
            android.util.Log.d("CachePerformance", "Cleared ${episodeIds.size} episodes from DOWNLOAD_EPISODE_CACHE")
            
            // Update parent index by removing deleted episode IDs
            val indexKey = "${com.lagradost.cloudstream3.utils.EPISODE_PARENT_INDEX}_$headerId"
            android.util.Log.d("CachePerformance", "Updating parent index: $indexKey")
            val currentIds = com.lagradost.cloudstream3.CloudStreamApp.getKey<Set<String>>(indexKey)
            android.util.Log.d("CachePerformance", "Current index size: ${currentIds?.size}")
            if (currentIds != null) {
                val updatedIds = currentIds - episodeIds.map { it.toString() }.toSet()
                if (updatedIds.isEmpty()) {
                    // Remove index entirely if no episodes left
                    com.lagradost.cloudstream3.CloudStreamApp.removeKey(indexKey)
                    android.util.Log.d("CachePerformance", "Removed parent index entirely (no episodes remaining)")
                } else {
                    com.lagradost.cloudstream3.CloudStreamApp.setKey(indexKey, updatedIds)
                    android.util.Log.d("CachePerformance", "Updated parent index: removed ${episodeIds.size} episodes, remaining ${updatedIds.size}")
                }
            } else {
                android.util.Log.w("CachePerformance", "Parent index not found for headerId: $headerId")
            }
            android.util.Log.d("CachePerformance", "=== DOWNLOADSCANNER: CACHE CLEARING COMPLETE ===")
            
            // Clear file info for each episode
            episodeIds.forEach { id ->
                com.lagradost.cloudstream3.CloudStreamApp.removeKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString())
                VideoDownloadManager.downloadStatus.remove(id)
                // Clear content URI keys
                com.lagradost.cloudstream3.CloudStreamApp.removeKey("CONTENT_URI_$id")
            }
            
            // Clear header
            com.lagradost.cloudstream3.CloudStreamApp.removeKey(DOWNLOAD_HEADER_CACHE, headerId.toString())
            
            Log.d(TAG, "Cleared cache for header ID $headerId, deleted ${episodesToDelete.size} episodes")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for header ID $headerId: ${e.message}", e)
        }
    }

    private data class ScannedFile(
        val folderPath: String,
        val name: String,
        val uri: android.net.Uri,
        val episodeNumber: Int,
    )

    /** Recursively walks [base] collecting video files with extractable episode numbers. */
    private fun walkTree(
        context: Context,
        base: SafeFile,
        folderPath: String,
        out: MutableList<ScannedFile>
    ) {
        val files = try {
            base.gotoDirectory(folderPath, createMissingDirectories = false)?.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Error listing '$folderPath': ${e.message}")
            null
        } ?: return

        for (item in files) {
            val name = try { item.name() } catch (e: Exception) { null } ?: continue
            val uri = try { item.uri() } catch (e: Exception) { null } ?: continue
            val extension = name.substringAfterLast('.', "").lowercase()
            if (videoExtensions.contains(extension)) {
                val episodeNumber = extractEpisodeNumber(name)
                if (episodeNumber != null) {
                    out.add(ScannedFile(folderPath, name, uri, episodeNumber))
                }
            } else if (item.isDirectory() == true || extension.isBlank()) {
                // Recurse into subfolders (subtitle files resolve to nothing)
                val sub = if (folderPath.isEmpty()) name else "$folderPath/$name"
                walkTree(context, base, sub, out)
            }
        }
    }

    /** Matches a header name against collected folder paths (normalized containment). */
    private fun matchFolder(
        filesByFolder: Map<String, List<ScannedFile>>,
        headerName: String
    ): List<ScannedFile>? {
        fun normalize(input: String): String = input
            .lowercase()
            .replace(Regex("[:\\-��_|/\\\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val normalizedName = normalize(headerName)
        if (normalizedName.isEmpty()) return null

        // Exact folder match first (shortest path wins)
        val exact = filesByFolder.entries
            .filter { (folder, _) -> normalize(folder.substringAfterLast('/')) == normalizedName }
            .minByOrNull { it.key.length }
        if (exact != null) return exact.value

        // Containment match (folder contains the show name, or vice versa)
        val best = filesByFolder.entries
            .filter { (folder, _) ->
                val normFolder = normalize(folder)
                (normFolder.contains(normalizedName) && normalizedName.length > 4) ||
                    (normalizedName.contains(normFolder) && normFolder.length > 4)
            }
            .minByOrNull { it.key.length }
        return best?.value
    }

    fun scanDownloads(context: Context, onComplete: (Int) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var foundCount = 0
            var matchedTotal = 0
            val countedHeaders = HashSet<Int>()
            try {
                val basePathSetting = getDownloadPath(context)

                val headers = context.getKeys(DOWNLOAD_HEADER_CACHE)
                    .mapNotNull { CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(it) }

                // Scope to the user's library (favorites + subscriptions + bookmarked) —
                // headers are also written for every show merely viewed/cached.
                val libraryIds = buildSet {
                    addAll(com.lagradost.cloudstream3.utils.DataStoreHelper.getAllFavorites().mapNotNull { it.id })
                    addAll(com.lagradost.cloudstream3.utils.DataStoreHelper.getAllSubscriptions().mapNotNull { it.id })
                    addAll(com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds()?.toList() ?: emptyList())
                }
                val scoped = if (libraryIds.isEmpty()) headers else headers.filter { it.id in libraryIds }
                Log.d(TAG, "Scanning ${scoped.size}/${headers.size} library entries")

                val episodesByParentId = context.getKeys(DOWNLOAD_EPISODE_CACHE)
                    .mapNotNull { CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(it) }
                    .groupBy { it.parentId }

                // Candidate roots: the default MediaStore Downloads dir first (the
                // "Download/" setting resolves to a raw path that scoped storage
                // won't list), then the configured path (SAF trees resolve fine).
                // No distinctBy(filePath) — different SafeFile implementations may
                // share a display path but point at different storage backends.
                val bases = buildList {
                    DownloadFileManagement.run {
                        getDefaultDir(context)?.let { add(it) }
                        basePathToFile(context, basePathSetting)?.let { add(it) }
                    }
                }.distinct()
                Log.d(TAG, "Base locations: ${bases.map { it.filePath() }}")

                for (base in bases) {
                    val baseStr = base.filePath() ?: continue
                    val files = mutableListOf<ScannedFile>()
                    walkTree(context, base, "", files)
                    Log.d(TAG, "Base $baseStr: ${files.size} video files on disk")
                    if (files.isEmpty()) continue

                    val filesByFolder = files.groupBy { it.folderPath }

                    for (header in scoped) {
                        if (header.name.isBlank()) continue
                        val matched = matchFolder(filesByFolder, header.name) ?: continue
                        val episodes = episodesByParentId[header.id].orEmpty()
                        if (episodes.isEmpty()) {
                            Log.d(TAG, "No cached episodes for '${header.name}', skipping")
                            continue
                        }
                        val matchedNumbers = matched.map { it.episodeNumber }.toSet()
                        val existingCount = episodes.count { ep ->
                            context.getKey<DownloadObjects.DownloadedFileInfo>(
                                VideoDownloadManager.KEY_DOWNLOAD_INFO, ep.id.toString()
                            ) != null
                        }
                        Log.d(
                            TAG,
                            "'${header.name}': ${matched.size} files (eps ${matchedNumbers.sorted().take(10)}), " +
                                "$existingCount already connected"
                        )
                        var newlyConnected = 0
                        var matchedHere = 0
                        for (file in matched) {
                            val ep = episodes.firstOrNull { it.episode == file.episodeNumber }
                                ?: continue
                            matchedHere++
                            if (context.getKey<DownloadObjects.DownloadedFileInfo>(
                                    VideoDownloadManager.KEY_DOWNLOAD_INFO, ep.id.toString()
                                ) != null
                            ) {
                                continue // already connected
                            }
                            val fileInfo = DownloadObjects.DownloadedFileInfo(
                                totalBytes = if (file.uri.toString().startsWith("content://")) -1 else 0,
                                relativePath = file.folderPath,
                                displayName = file.name,
                                extraInfo = file.uri.toString(),
                                basePath = baseStr
                            )
                            context.setKey(
                                VideoDownloadManager.KEY_DOWNLOAD_INFO, ep.id.toString(), fileInfo
                            )
                            context.setKey("CONTENT_URI_${ep.id}", file.uri.toString())
                            VideoDownloadManager.setDownloadStatus(
                                ep.id, VideoDownloadManager.DownloadType.IsDone, context
                            )
                            newlyConnected++
                            foundCount++
                        }
                        // Count this header once even if multiple bases see it
                        if (countedHeaders.add(header.id)) matchedTotal += matchedHere
                        Log.d(
                            TAG,
                            "'${header.name}': +$newlyConnected newly connected " +
                                "($matchedHere matched here)"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning downloads: ${e.message}", e)
            }
            // Report EVERY matched episode (already-connected included) so a scan
            // over an already-bound library shows "N entries found" instead of 0.
            onComplete(matchedTotal)
        }
    }
}