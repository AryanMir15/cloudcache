package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.ui.player.extractEpisodeNumber
import com.lagradost.cloudstream3.ui.player.generateUniqueLocalId
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
            
            // Clear episode cache
            episodesToDelete.forEach { ep ->
                com.lagradost.cloudstream3.CloudStreamApp.removeKey(DOWNLOAD_EPISODE_CACHE, ep.id.toString())
            }
            
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

    private fun scanFolderRecursively(
        context: Context,
        folderPath: String,
        basePath: String?,
        folderName: String,
        episodesByParentId: Map<Int, List<DownloadObjects.DownloadEpisodeCached>>,
        matchingHeaderIds: Set<Int>,
        headerId: Int,
        existingIds: Set<Int>
    ): Int {
        var foundCount = 0
        
        try {
            val files = DownloadFileManagement.getFolder(context, folderPath, basePath)
            
            if (files != null && files.isNotEmpty()) {
                Log.d(TAG, "Scanning folder: $folderPath, found ${files.size} items")
                
                files.forEach { (name, uri) ->
                    val extension = name.substringAfterLast('.', "").lowercase()
                    
                    if (videoExtensions.contains(extension)) {
                        val episodeNumber = extractEpisodeNumber(name)
                        if (episodeNumber != null) {
                            Log.d(TAG, "Found episode $episodeNumber: $name in $folderPath")
                            
                            // Find existing episode by checking all matching header IDs
                            val existingEpisode = matchingHeaderIds.firstNotNullOfOrNull { headerId ->
                                episodesByParentId[headerId]?.firstOrNull { it.episode == episodeNumber }
                            }
                            
                            if (existingEpisode != null) {
                                // Update existing episode with DownloadedFileInfo
                                val fileInfo = DownloadObjects.DownloadedFileInfo(
                                    totalBytes = 0,
                                    relativePath = "$folderPath/$name",
                                    displayName = name,
                                    extraInfo = uri.toString(),
                                    basePath = basePath
                                )
                                // Use correct constant instead of string literal to match getDownloadFileInfo read path
                                context.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, existingEpisode.id.toString(), fileInfo)
                                Log.d(TAG, "Cached DownloadedFileInfo with key: ${VideoDownloadManager.KEY_DOWNLOAD_INFO}/${existingEpisode.id}")
                                
                                // Cache content URI in CONTENT_URI_* key (matches ResultViewModel2 behavior)
                                val contentUriKey = "CONTENT_URI_${existingEpisode.id}"
                                // Use path-level write instead of DOWNLOAD_EPISODE_CACHE namespace
                                context.setKey(contentUriKey, uri.toString())
                                Log.d(TAG, "Cached content URI with key: $contentUriKey")
                                
                                // Update downloadStatus using helper to persist immediately
                                VideoDownloadManager.setDownloadStatus(existingEpisode.id, VideoDownloadManager.DownloadType.IsDone, context)
                                
                                Log.d(TAG, "Updated episode $episodeNumber with ID ${existingEpisode.id}")
                                foundCount++
                            } else {
                                // Episode not in library - skip it
                                // We only update episodes that are already in the library (from the API)
                                // because the episode list uses API episode IDs to check download status
                                Log.d(TAG, "Episode $episodeNumber not in library, skipping (add to library first)")
                            }
                        }
                    } else {
                        // It's a directory, scan it recursively
                        val subfolderPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
                        foundCount += scanFolderRecursively(
                            context,
                            subfolderPath,
                            basePath,
                            folderName,
                            episodesByParentId,
                            matchingHeaderIds,
                            headerId,
                            existingIds
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning folder $folderPath: ${e.message}", e)
        }
        
        return foundCount
    }

    fun scanDownloads(context: Context, onComplete: (Int) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var foundCount = 0
            try {
                val basePath = getDownloadPath(context)
                Log.d(TAG, "Scanning download directory with basePath: $basePath")
                
                // Get all library headers to match against
                val headerKeys = context.getKeys(DOWNLOAD_HEADER_CACHE)
                Log.d(TAG, "Found ${headerKeys.size} cached headers in library")
                
                // Build headers list and group by name to handle duplicates
                val headers = headerKeys.mapNotNull { key ->
                    CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(key)
                }
                val headersByName = headers.groupBy { it.name }
                
                // Log all library header names
                Log.d(TAG, "=== Library Headers ===")
                headers.forEach { header ->
                    Log.d(TAG, "Library header: ${header.name} (id: ${header.id})")
                }
                Log.d(TAG, "=== End Library Headers ===")
                
                // Pre-build episode map by parentId for efficient lookup
                val episodesByParentId = context.getKeys(DOWNLOAD_EPISODE_CACHE)
                    .mapNotNull { CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(it) }
                    .groupBy { it.parentId }
                
                // Get all existing episode IDs for collision detection
                val existingIds = context.getKeys(DOWNLOAD_EPISODE_CACHE)
                    .mapNotNull { CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(it) }
                    .map { it.id }
                    .toSet()
                
                Log.d(TAG, "Built episode map with ${episodesByParentId.size} parent IDs, ${existingIds.size} existing IDs")
                
                // Track which folder names we've already scanned to avoid duplicates
                val scannedFolders = mutableSetOf<String>()
                
                // Try to scan each unique folder name
                headersByName.forEach { (folderName, headerList) ->
                    if (folderName in scannedFolders) {
                        Log.d(TAG, "Already scanned $folderName, skipping")
                        return@forEach
                    }
                    scannedFolders.add(folderName)
                    
                    // Get all header IDs that match this folder name
                    val matchingHeaderIds = headerList.map { it.id }.toSet()
                    Log.d(TAG, "Scanning folder: $folderName (matching header IDs: $matchingHeaderIds)")
                    
                    // Try direct path first
                    var folderPath = folderName
                    var files: List<Pair<String, android.net.Uri>>? = null
                    try {
                        files = DownloadFileManagement.getFolder(context, folderPath, basePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accessing folder $folderPath: ${e.message}", e)
                    }
                    
                    // If not found, try under Anime/
                    if (files.isNullOrEmpty()) {
                        folderPath = "Anime/$folderName"
                        try {
                            files = DownloadFileManagement.getFolder(context, folderPath, basePath)
                            Log.d(TAG, "Trying Anime/$folderName, found: ${files?.size}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing folder Anime/$folderName: ${e.message}", e)
                        }
                    }
                    
                    // If still not found, try fuzzy matching with Anime folder contents
                    if (files.isNullOrEmpty()) {
                        try {
                            val animeFolderFiles = DownloadFileManagement.getFolder(context, "Anime", basePath)
                            
                            if (animeFolderFiles != null) {
                                val matchedFolder = animeFolderFiles.firstOrNull { (name, _) ->
                                    name.equals(folderName, ignoreCase = true) || 
                                    folderName.equals(name, ignoreCase = true) ||
                                    name.contains(folderName, ignoreCase = true) ||
                                    folderName.contains(name, ignoreCase = true)
                                }
                                
                                if (matchedFolder != null) {
                                    Log.d(TAG, "Fuzzy matched: ${matchedFolder.first} to $folderName")
                                    folderPath = "Anime/${matchedFolder.first}"
                                    files = DownloadFileManagement.getFolder(context, folderPath, basePath)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing Anime folder for fuzzy matching: ${e.message}", e)
                        }
                    }
                    
                    if (files != null && files.isNotEmpty()) {
                        // Use the first header ID for this folder (they all have the same name)
                        val headerId = headerList.first().id
                        
                        // Scan recursively to handle subfolders
                        foundCount += scanFolderRecursively(
                            context,
                            folderPath,
                            basePath,
                            folderName,
                            episodesByParentId,
                            matchingHeaderIds,
                            headerId,
                            existingIds
                        )
                    } else {
                        Log.d(TAG, "No files found for $folderName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning downloads: ${e.message}", e)
            }
            
            onComplete(foundCount)
        }
    }
}
