package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.safefile.SafeFile

/**
 * Extension functions to convert between different episode representations.
 * This provides a clean, testable way to convert ExtractorUri to ResultEpisode.
 */

/**
 * Converts an ExtractorUri (local playback) to ResultEpisode with cached metadata.
 * This is used to provide rich metadata in the player's episode overlay for local files.
 */
fun ExtractorUri.toResultEpisode(
    index: Int,
    cachedEpisode: DownloadObjects.DownloadEpisodeCached? = null,
    cachedHeader: DownloadObjects.DownloadHeaderCached? = null
): ResultEpisode {
    val posDur = getViewPos(id ?: 0)
    
    android.util.Log.d(
        "EpisodeConverters",
        "Converting ExtractorUri to ResultEpisode: episode=${episode}, " +
                "cached=${cachedEpisode?.name}, " +
                "poster=${cachedEpisode?.poster != null}, " +
                "header=${cachedHeader?.name}"
    )
    
    return ResultEpisode(
        headerName = headerName ?: cachedHeader?.name ?: "",
        name = cachedEpisode?.name ?: name ?: "Episode ${episode ?: (index + 1)}",
        poster = cachedEpisode?.poster,
        episode = episode ?: (index + 1),
        seasonIndex = season?.let { it - 1 } ?: cachedEpisode?.season?.let { it - 1 },
        season = season ?: cachedEpisode?.season,
        data = "",
        apiName = cachedHeader?.apiName ?: "Local",
        id = id ?: 0,
        index = index,
        position = posDur?.position ?: 0,
        duration = posDur?.duration ?: 0,
        score = cachedEpisode?.score,
        description = cachedEpisode?.description,
        isFiller = null,
        tvType = tvType ?: cachedHeader?.type ?: TvType.Anime,
        parentId = parentId ?: cachedEpisode?.parentId ?: 0,
        videoWatchState = getVideoWatchState(id ?: 0) ?: VideoWatchState.None,
        totalEpisodeIndex = episode
    )
}

/**
 * Loads cached episode data for a given episode ID.
 */
fun loadCachedEpisode(episodeId: Int?): DownloadObjects.DownloadEpisodeCached? {
    return episodeId?.let { id ->
        CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(
            DOWNLOAD_EPISODE_CACHE,
            id.toString()
        )
    }
}

/**
 * Loads cached header (show-level) data for a given parent ID.
 */
fun loadCachedHeader(parentId: Int?): DownloadObjects.DownloadHeaderCached? {
    return parentId?.let { id ->
        CloudStreamApp.getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE,
            id.toString()
        )
    }
}

/**
 * Loads all cached episodes for a given show (parent ID).
 * This is used to show the full episode list when playing local files.
 */
fun loadAllCachedEpisodes(parentId: Int?): List<DownloadObjects.DownloadEpisodeCached> {
    if (parentId == null) return emptyList()
    
    val allKeys = CloudStreamApp.getKeys(DOWNLOAD_EPISODE_CACHE)
    android.util.Log.d("EpisodeConverters", "loadAllCachedEpisodes: parentId=$parentId, total keys in cache=${allKeys?.size}")
    
    // Check first few keys to see what's stored
    allKeys?.take(5)?.forEach { key ->
        android.util.Log.d("EpisodeConverters", "Sample cache key: $key")
    }
    
    val allEpisodes = allKeys?.mapNotNull { key ->
        // Keys from getKeys already include cache name prefix, so use getKey without cache name parameter
        val data = CloudStreamApp.getKey<DownloadObjects.DownloadEpisodeCached>(key)
        when {
            data == null -> {
                // Corrupted entry - clean it up
                android.util.Log.d("EpisodeConverters", "Corrupted cache entry for key: $key, cleaning up")
                CloudStreamApp.removeKey(key)
                null
            }
            data.id == 0 || data.episode <= 0 -> {
                // Invalid data - clean it up
                android.util.Log.d("EpisodeConverters", "Invalid cache entry for key: $key (id=${data.id}, episode=${data.episode}), cleaning up")
                CloudStreamApp.removeKey(key)
                null
            }
            else -> data
        }
    } ?: emptyList()
    
    android.util.Log.d("EpisodeConverters", "loadAllCachedEpisodes: loaded ${allEpisodes.size} episodes, parentIds=${allEpisodes.map { it.parentId }.distinct()}")
    
    val filtered = allEpisodes.filter { it.parentId == parentId }
    android.util.Log.d("EpisodeConverters", "loadAllCachedEpisodes: filtered to ${filtered.size} episodes with matching parentId")
    
    // Deduplicate by episode number (keep the first occurrence)
    val deduplicated = filtered.distinctBy { it.episode }
    android.util.Log.d("EpisodeConverters", "loadAllCachedEpisodes: deduplicated to ${deduplicated.size} episodes")
    
    return deduplicated.sortedBy { it.episode }
}

/**
 * Extracts the episode number from a filename.
 * Supports patterns like:
 * - "Episode 1", "Episode 1.mkv"
 * - "E1", "E01"
 * - "S01E01"
 * - "1. Title", "01 Title"
 */
fun extractEpisodeNumber(filename: String): Int? {
    // Pattern 1: Episode X (any format)
    val episodePattern = Regex("(?i)episode[\\s_-]*(\\d+)")
    episodePattern.find(filename)?.let {
        return it.groupValues[1].toInt()
    }
    
    // Pattern 2: S01E01 format
    val seasonEpPattern = Regex("S\\d+E(\\d+)", RegexOption.IGNORE_CASE)
    seasonEpPattern.find(filename)?.let {
        return it.groupValues[1].toInt()
    }
    
    // Pattern 3: Just the number at start (e.g., "1. Title", "01 Title")
    val startsWithNumber = Regex("^(\\d+)[.\\s_-]")
    startsWithNumber.find(filename)?.let {
        return it.groupValues[1].toInt()
    }
    
    // Pattern 4: E01 or E1 format
    val ePattern = Regex("\\bE(\\d+)\\b", RegexOption.IGNORE_CASE)
    ePattern.find(filename)?.let {
        return it.groupValues[1].toInt()
    }
    
    return null
}

/**
 * Data class to hold raw scanned episode information from folder scanning.
 */
data class ScannedEpisode(
    val episodeNumber: Int,
    val fileName: String,
    val fileUri: Uri,
    val isDirectory: Boolean,
    val actualVideoUri: Uri  // Resolved video file (handles EISDIR case)
)

private val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv")

/**
 * Scans a folder for video files and returns episode information.
 * Skips episodes that are already cached (by episode number).
 */
fun scanFolderForEpisodes(
    context: Context,
    basePath: String,
    relativePath: String,
    existingEpisodeNumbers: Set<Int>
): List<ScannedEpisode> {
    val scanned = mutableListOf<ScannedEpisode>()
    
    try {
        val baseFile = SafeFile.fromUri(context, Uri.parse(basePath))
        val targetDir = baseFile?.gotoDirectory(relativePath, createMissingDirectories = false)
        
        if (targetDir == null || targetDir.exists() != true) {
            Log.d("EpisodeConverters", "Target dir not found: base=$basePath, rel=$relativePath")
            return emptyList()
        }
        
        val allFiles = targetDir.listFiles() ?: return emptyList()
        Log.d("EpisodeConverters", "Found ${allFiles.size} items in folder")
        
        allFiles.forEach { file ->
            val name = file.name() ?: return@forEach
            val lowerName = name.lowercase()
            
            // Check if video file or directory
            val extension = name.substringAfterLast('.', "").lowercase()
            val isVideo = videoExtensions.contains(extension)
            val isDirectory = file.isDirectory() == true
            
            if (!isVideo && !isDirectory) return@forEach
            
            val episodeNumber = extractEpisodeNumber(lowerName)
            if (episodeNumber == null) {
                Log.d("EpisodeConverters", "Could not extract episode number from: $name")
                return@forEach
            }
            
            // Skip if already cached (we already have this episode)
            if (episodeNumber in existingEpisodeNumbers) {
                Log.d("EpisodeConverters", "Episode $episodeNumber already cached, skipping")
                return@forEach
            }
            
            try {
                val fileUri = file.uriOrThrow()
                
                // Handle directory case (SAF EISDIR fix)
                val actualVideoUri = if (isDirectory) {
                    val innerFiles = file.listFiles()
                    val videoFile = innerFiles?.firstOrNull { inner ->
                        val innerName = inner.name()?.lowercase() ?: ""
                        videoExtensions.any { ext -> innerName.endsWith(".$ext") }
                    }
                    if (videoFile != null) {
                        Log.d("EpisodeConverters", "Found video inside directory: ${videoFile.name()}")
                        videoFile.uriOrThrow()
                    } else {
                        Log.w("EpisodeConverters", "No video file found in directory: $name")
                        fileUri
                    }
                } else fileUri
                
                val cleanName = name.removeSuffix(".$extension")
                
                scanned.add(ScannedEpisode(
                    episodeNumber = episodeNumber,
                    fileName = cleanName,
                    fileUri = fileUri,
                    isDirectory = isDirectory,
                    actualVideoUri = actualVideoUri
                ))
                
                Log.d("EpisodeConverters", "Scanned episode $episodeNumber: $cleanName")
                
            } catch (e: Exception) {
                Log.e("EpisodeConverters", "Error processing file: $name", e)
            }
        }
        
    } catch (e: Exception) {
        Log.e("EpisodeConverters", "Error scanning folder", e)
    }
    
    return scanned.distinctBy { it.episodeNumber }.sortedBy { it.episodeNumber }
}

/**
 * Generates a unique negative ID for locally-scanned episodes.
 * Uses episode number + parentId hash in negative space.
 */
fun generateLocalEpisodeId(episodeNumber: Int, parentId: Int): Int {
    // Generate ID in negative space: -(episodeNumber + parentId * 1000)
    // This keeps local IDs distinct from API IDs (which are typically positive)
    val baseId = -(episodeNumber + kotlin.math.abs(parentId) * 1000)
    
    // Additional safety: ensure it's in valid negative range
    // Int.MIN_VALUE is -2^31, we stay well above that
    return if (baseId < -1_000_000_000) {
        // For extremely large parentIds, use simpler hash
        -(episodeNumber + (parentId % 1_000_000) * 1000)
    } else baseId
}

/**
 * Validates that a generated local ID doesn't collide with an existing cached episode.
 * If collision detected, generates alternative ID.
 */
fun generateUniqueLocalId(
    episodeNumber: Int,
    parentId: Int,
    existingIds: Set<Int>
): Int {
    val baseId = generateLocalEpisodeId(episodeNumber, parentId)
    
    // Check if this ID is already used by a different episode
    if (baseId !in existingIds) return baseId
    
    // Collision detected - try variations
    // Try: -((episodeNumber * 10) + parentId * 1000) for alternative space
    val altId1 = -((episodeNumber * 10) + kotlin.math.abs(parentId) * 1000)
    if (altId1 !in existingIds) return altId1
    
    // Try with timestamp-based offset
    val altId2 = -(episodeNumber + kotlin.math.abs(parentId) * 1000 + (System.currentTimeMillis() % 1000).toInt())
    if (altId2 !in existingIds) return altId2
    
    // Last resort: random offset
    val altId3 = -(episodeNumber + kotlin.math.abs(parentId) * 1000 + (1..999).random())
    return altId3
}
