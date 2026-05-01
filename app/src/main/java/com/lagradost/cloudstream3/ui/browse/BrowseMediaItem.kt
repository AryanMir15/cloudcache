package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.*

/**
 * Unified media item wrapper for Browse tab results.
 * Normalizes data from both AniList and TMDB APIs into a common format.
 */
data class BrowseMediaItem(
    val id: String,           // Provider-prefixed ID: "anilist_123" or "tmdb_456"
    val title: String,
    val posterUrl: String?,
    val type: BrowseMediaType,
    val provider: FilterProvider,
    val sourceData: Any? = null  // Raw provider data for detail navigation
) {
    /**
     * Extracts the numeric ID for the original provider
     */
    fun getProviderNumericId(): Int? {
        return id.substringAfter("_").toIntOrNull()
    }
}

enum class FilterProvider {
    ANILIST,
    TMDB
}

enum class TmdbFormat {
    MOVIE,
    TV
}

enum class BrowseMediaType {
    ANIME,
    MOVIE,
    TV_SHOW,
    OVA,
    SPECIAL,
    ONA,
    TV_SHORT,
    UNKNOWN;
    
    companion object {
        fun fromAniListFormat(format: String?): BrowseMediaType {
            return when (format?.uppercase()) {
                "TV" -> ANIME
                "TV_SHORT" -> TV_SHORT
                "MOVIE" -> MOVIE
                "SPECIAL" -> SPECIAL
                "OVA" -> OVA
                "ONA" -> ONA
                else -> UNKNOWN
            }
        }
        
        fun fromTmdbMediaType(mediaType: String?, isMovie: Boolean): BrowseMediaType {
            return when {
                isMovie -> MOVIE
                mediaType == "tv" -> TV_SHOW
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Convert BrowseMediaItem to SearchResponse for adapter compatibility.
 * Uses data class constructors directly since newXxxSearchResponse are MainAPI extensions.
 */
@Suppress("DEPRECATION_ERROR")
fun BrowseMediaItem.toSearchResponse(): SearchResponse {
    val numericId = this.getProviderNumericId()
    val apiName = when (this.provider) {
        FilterProvider.ANILIST -> "AniList"
        FilterProvider.TMDB -> "TMDB"
    }
    
    return when (this.type) {
        BrowseMediaType.ANIME -> AnimeSearchResponse(
            name = this.title,
            url = this.id,
            apiName = apiName,
            type = TvType.Anime,
            id = numericId,
            posterUrl = this.posterUrl
        )
        BrowseMediaType.MOVIE -> MovieSearchResponse(
            name = this.title,
            url = this.id,
            apiName = apiName,
            type = TvType.Movie,
            id = numericId,
            posterUrl = this.posterUrl
        )
        BrowseMediaType.TV_SHOW -> TvSeriesSearchResponse(
            name = this.title,
            url = this.id,
            apiName = apiName,
            type = TvType.TvSeries,
            id = numericId,
            posterUrl = this.posterUrl
        )
        else -> MovieSearchResponse(
            name = this.title,
            url = this.id,
            apiName = apiName,
            type = TvType.Movie,
            id = numericId,
            posterUrl = this.posterUrl
        )
    }
}

/**
 * Convert list of BrowseMediaItem to list of SearchResponse
 */
fun List<BrowseMediaItem>.toSearchResponses(): List<SearchResponse> {
    return this.map { it.toSearchResponse() }
}
