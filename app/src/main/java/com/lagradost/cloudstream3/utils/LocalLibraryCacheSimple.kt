package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

// Data classes for caching using DataStore pattern
data class CachedSeriesData(
    val id: String,
    val name: String,
    val posterPath: String?,
    val bannerPath: String?,
    val description: String?,
    val rating: Int?,
    val year: Int?,
    val status: String?,
    val cachedAt: Long,
    val lastRefetchedAt: Long
)

data class CachedEpisodeData(
    val id: String,
    val seriesId: String,
    val name: String,
    val posterPath: String?,
    val description: String?,
    val episode: Int,
    val season: Int?,
    val duration: Long?,
    val skipIntroStart: Long?,
    val skipIntroEnd: Long?,
    val filePath: String?,
    val cachedAt: Long
)

data class CacheStatusData(
    val seriesId: String,
    val postersCached: Boolean,
    val episodesCached: Boolean,
    val metadataCached: Boolean,
    val totalSize: Long,
    val lastAccessed: Long
)

class LocalLibraryCacheSimple(private val context: Context) {
    
    companion object {
        const val CACHE_FOLDER = "local_cache"
        const val POSTERS_FOLDER = "posters"
        const val EPISODES_FOLDER = "episodes"
        const val METADATA_FOLDER = "metadata"
        
        private const val SERIES_KEY_PREFIX = "local_cache_series_"
        private const val EPISODES_KEY_PREFIX = "local_cache_episodes_"
        private const val STATUS_KEY_PREFIX = "local_cache_status_"
    }
    
    private val cacheDir = File(context.filesDir, CACHE_FOLDER)
    
    init {
        // Ensure cache directories exist
        File(cacheDir, POSTERS_FOLDER).mkdirs()
        File(cacheDir, EPISODES_FOLDER).mkdirs()
        File(cacheDir, METADATA_FOLDER).mkdirs()
    }
    
    suspend fun cacheSeriesData(
        seriesId: String,
        source: String = "default",
        name: String,
        posterUrl: String?,
        bannerUrl: String?,
        description: String?,
        rating: Int?,
        year: Int?,
        status: String?,
        episodes: List<com.lagradost.cloudstream3.ui.result.ResultEpisode>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Cache poster
            val posterPath = posterUrl?.let { downloadImage(it, "posters/${source}_${seriesId}_poster.jpg") }
            val bannerPath = bannerUrl?.let { downloadImage(it, "posters/${source}_${seriesId}_banner.jpg") }
            
            // 2. Cache series metadata
            val cachedSeries = CachedSeriesData(
                id = seriesId,
                name = name,
                posterPath = posterPath,
                bannerPath = bannerPath,
                description = description,
                rating = rating,
                year = year,
                status = status,
                cachedAt = System.currentTimeMillis(),
                lastRefetchedAt = System.currentTimeMillis()
            )
            
            // Include source in key to prevent collision between different sources with same ID
            setKey("${SERIES_KEY_PREFIX}${source}_${seriesId}", cachedSeries)
            
            // 3. Cache episodes
            val cachedEpisodes = episodes.map { episode ->
                val episodePosterPath = downloadEpisodeThumbnail(episode, seriesId)
                CachedEpisodeData(
                    id = episode.id.toString(),
                    seriesId = seriesId,
                    name = episode.name ?: "",
                    posterPath = episodePosterPath,
                    description = episode.description,
                    episode = episode.episode,
                    season = episode.season,
                    duration = episode.duration,
                    skipIntroStart = null,
                    skipIntroEnd = null,
                    filePath = null, // Will be set when downloaded
                    cachedAt = System.currentTimeMillis()
                )
            }
            
            setKey("${EPISODES_KEY_PREFIX}${source}_${seriesId}", cachedEpisodes)
            
            // 4. Update cache status
            val cacheStatus = CacheStatusData(
                seriesId = seriesId,
                postersCached = posterPath != null,
                episodesCached = true,
                metadataCached = true,
                totalSize = calculateCacheSize(seriesId),
                lastAccessed = System.currentTimeMillis()
            )
            
            setKey("${STATUS_KEY_PREFIX}${source}_${seriesId}", cacheStatus)
            
            Result.success(Unit)
        } catch (e: Exception) {
            logError(e)
            Result.failure(e)
        }
    }
    
    fun getCachedSeries(seriesId: String, source: String = "default"): CachedSeriesData? {
        return getKey<CachedSeriesData>("${SERIES_KEY_PREFIX}${source}_${seriesId}")
    }
    
    fun getAllCachedSeries(): List<CachedSeriesData> {
        val allKeys = getKeys("local_cache_series_")
        return allKeys?.mapNotNull { key ->
            getKey<CachedSeriesData>(key)
        }?.filterNotNull() ?: emptyList()
    }
    
    fun getCachedEpisodes(seriesId: String, source: String = "default"): List<CachedEpisodeData> {
        return getKey<List<CachedEpisodeData>>("${EPISODES_KEY_PREFIX}${source}_${seriesId}") ?: emptyList()
    }
    
    fun updateEpisodeFilePath(seriesId: String, source: String = "default", episodeId: String, filePath: String) {
        val episodes = getCachedEpisodes(seriesId, source).toMutableList()
        val episodeIndex = episodes.indexOfFirst { it.id == episodeId }
        if (episodeIndex != -1) {
            episodes[episodeIndex] = episodes[episodeIndex].copy(filePath = filePath)
            setKey("${EPISODES_KEY_PREFIX}${source}_${seriesId}", episodes)
        }
    }

    suspend fun updatePoster(seriesId: String, source: String = "default", posterUrl: String?, bannerUrl: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MetadataSwap", "updatePoster called - seriesId: $seriesId, source: $source, posterUrl: $posterUrl, bannerUrl: $bannerUrl")
            val cachedSeries = getCachedSeries(seriesId, source)
            android.util.Log.d("MetadataSwap", "updatePoster - cachedSeries: ${cachedSeries?.name}")
            if (cachedSeries == null) {
                android.util.Log.w("MetadataSwap", "updatePoster - Series not cached in local library")
                return@withContext Result.failure(Exception("Series not cached"))
            }
            
            // Download new poster
            val newPosterPath = posterUrl?.let { downloadImage(it, "posters/${source}_${seriesId}_poster.jpg") }
            android.util.Log.d("MetadataSwap", "updatePoster - newPosterPath: $newPosterPath")
            
            // Download new banner if provided
            val newBannerPath = bannerUrl?.let { downloadImage(it, "posters/${source}_${seriesId}_banner.jpg") }
            android.util.Log.d("MetadataSwap", "updatePoster - newBannerPath: $newBannerPath")
            
            // Update cached series data with new poster and banner
            val updatedSeries = cachedSeries.copy(
                posterPath = newPosterPath,
                bannerPath = newBannerPath,
                lastRefetchedAt = System.currentTimeMillis()
            )
            
            setKey("${SERIES_KEY_PREFIX}${source}_${seriesId}", updatedSeries)
            android.util.Log.d("MetadataSwap", "updatePoster - Updated cached series with new poster and banner")
            
            // Update cache status
            val cacheStatus = getCacheStatus(seriesId, source)
            if (cacheStatus != null) {
                val updatedStatus = cacheStatus.copy(
                    postersCached = newPosterPath != null || newBannerPath != null,
                    lastAccessed = System.currentTimeMillis()
                )
                setKey("${STATUS_KEY_PREFIX}${source}_${seriesId}", updatedStatus)
            }
            
            android.util.Log.d("MetadataSwap", "updatePoster - Success")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MetadataSwap", "updatePoster - Error: ${e.message}", e)
            logError(e)
            Result.failure(e)
        }
    }
    
    private suspend fun downloadImage(url: String, filename: String): String? = withContext(Dispatchers.IO) {
        try {
            val posterFile = File(cacheDir, filename)
            if (posterFile.exists()) return@withContext posterFile.absolutePath
            
            val connection = URL(url).openConnection()
            connection.getInputStream().use { input ->
                FileOutputStream(posterFile).use { output ->
                    input.copyTo(output)
                }
            }
            posterFile.absolutePath
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
    
    private suspend fun downloadEpisodeThumbnail(
        episode: com.lagradost.cloudstream3.ui.result.ResultEpisode,
        seriesId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Try to get thumbnail from episode poster first
            episode.poster?.let { posterUrl ->
                downloadImage(posterUrl, "episodes/${seriesId}_${episode.id}_thumb.jpg")?.let { return@withContext it }
            }
            
            // If no poster, we'll need to generate from video file when available
            null
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
    
    suspend fun generateEpisodeThumbnail(videoFile: File, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            
            // Get frame at 20 seconds (20,000,000 microseconds)
            val bitmap = retriever.getFrameAtTime(20_000_000)
            retriever.release()
            
            if (bitmap != null) {
                val thumbnailFile = File(cacheDir, outputPath)
                FileOutputStream(thumbnailFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
    
    private fun calculateCacheSize(seriesId: String): Long {
        val posterDir = File(cacheDir, POSTERS_FOLDER)
        val episodesDir = File(cacheDir, EPISODES_FOLDER)
        
        var totalSize = 0L
        
        // Calculate poster sizes
        posterDir.listFiles { file -> file.name.startsWith(seriesId) }?.forEach { file ->
            totalSize += file.length()
        }
        
        // Calculate episode thumbnail sizes
        episodesDir.listFiles { file -> file.name.startsWith(seriesId) }?.forEach { file ->
            totalSize += file.length()
        }
        
        return totalSize
    }
    
    fun getCacheStatus(seriesId: String, source: String = "default"): CacheStatusData? {
        return getKey<CacheStatusData>("${STATUS_KEY_PREFIX}${source}_${seriesId}")
    }
    
    fun clearCache(seriesId: String, source: String = "default") {
        removeKey("${SERIES_KEY_PREFIX}${source}_${seriesId}")
        removeKey("${EPISODES_KEY_PREFIX}${source}_${seriesId}")
        removeKey("${STATUS_KEY_PREFIX}${source}_${seriesId}")
        
        // Delete cached files
        val filePrefix = "${source}_${seriesId}"
        File(cacheDir, POSTERS_FOLDER).listFiles { file -> file.name.startsWith(filePrefix) }?.forEach { it.delete() }
        File(cacheDir, EPISODES_FOLDER).listFiles { file -> file.name.startsWith(filePrefix) }?.forEach { it.delete() }
    }
    
    fun getTotalCacheSize(): Long {
        val posterDir = File(cacheDir, POSTERS_FOLDER)
        val episodesDir = File(cacheDir, EPISODES_FOLDER)
        
        return (posterDir.walkTopDown().sumOf { it.length() } + 
               episodesDir.walkTopDown().sumOf { it.length() })
    }
}
