package com.lagradost.cloudstream3.syncproviders.providers

import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.browse.BrowseMediaItem
import com.lagradost.cloudstream3.ui.browse.BrowseMediaType
import com.lagradost.cloudstream3.ui.browse.FilterProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

/**
 * TMDB API service for Browse tab integration.
 * Provides discover endpoints for Movies and TV shows with genre filtering.
 */
object TmdbApi {
    
    const val BASE_URL = "https://api.themoviedb.org/3"
    const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
    
    // Preference key for API key storage
    const val API_KEY_PREF = "tmdb_api_key"
    
    // Cached genre maps
    private var movieGenreCache: Map<String, String>? = null
    private var tvGenreCache: Map<String, String>? = null
    
    /**
     * Get TMDB display language from SharedPreferences
     * Defaults to en-US if not set
     */
    fun getDisplayLanguage(context: android.content.Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val language = prefs.getString("tmdb_display_language_key", "en-US") ?: "en-US"
        android.util.Log.d("TMDB_API_DEBUG", "getDisplayLanguage: retrieved language=$language")
        return language
    }
    
    /**
     * Build TMDB query parameters with region-based logic for Korean providers
     * Automatically forces KR region when Korean native providers are selected
     */
    fun buildTmdbQuery(selectedProviders: List<String>, country: String): Map<String, String> {
        val queryMap = mutableMapOf<String, String>()
        
        // Identify native Korean providers (TMDB IDs)
        val nativeKRProviders = listOf("356", "1796", "2416", "97", "82", "337")
        
        // Logic: If any native KR provider is selected, force region to KR
        val hasKRProvider = selectedProviders.any { it in nativeKRProviders }
        val activeRegion = if (hasKRProvider) "KR" else country
        
        queryMap["watch_region"] = activeRegion
        queryMap["with_watch_providers"] = selectedProviders.joinToString("|")
        
        android.util.Log.d("TMDB_API_DEBUG", "buildTmdbQuery: hasKRProvider=$hasKRProvider, activeRegion=$activeRegion")
        android.util.Log.d("TMDB_API_DEBUG", "buildTmdbQuery: selectedProviders=$selectedProviders")
        
        return queryMap
    }
    
    /**
     * Data classes for TMDB API responses
     */
    data class GenreResponse(
        @JsonProperty("genres") val genres: List<Genre>?
    )
    
    data class Genre(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?
    )
    
    /**
     * TMDB Keyword Search Response
     */
    data class KeywordSearchResponse(
        val results: List<KeywordResult>?
    )

    /**
     * TMDB Keyword Result
     */
    data class KeywordResult(
        val id: Int,
        val name: String
    )
    
    data class SearchMultiResponse(
        @JsonProperty("results") val results: List<SearchMultiResult>?
    )
    
    data class SearchMultiResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("media_type") val mediaType: String?,  // "movie", "tv", "person"
        @JsonProperty("adult") val adult: Boolean = false
    )
    
    data class DiscoverResponse(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("results") val results: List<DiscoverResult>?,
        @JsonProperty("total_pages") val totalPages: Int?,
        @JsonProperty("total_results") val totalResults: Int?
    )
    
    data class DiscoverResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,           // For movies
        @JsonProperty("name") val name: String?,            // For TV
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("vote_count") val voteCount: Int?,
        @JsonProperty("release_date") val releaseDate: String?,     // For movies
        @JsonProperty("first_air_date") val firstAirDate: String?,   // For TV
        @JsonProperty("genre_ids") val genreIds: List<Int>?,
        @JsonProperty("media_type") val mediaType: String?
    )
    
    /**
     * Get the stored API key from preferences
     */
    fun getApiKey(): String? {
        // This will be accessed via SharedPreferences in the fragment
        // For now, we'll pass the key as parameter to methods
        return null
    }
    
    /**
     * Fetch movie genres from TMDB.
     * Returns map of ID (as string) to name.
     */
    suspend fun getMovieGenres(apiKey: String): Map<String, String>? {
        // Return cached if available
        movieGenreCache?.let { return it }
        
        return try {
            val url = "$BASE_URL/genre/movie/list?api_key=$apiKey"
            val response = app.get(url, timeout = 10000)
            val data = response.text
            
            android.util.Log.d("TMDB_API", "getMovieGenres: url=$url")
            android.util.Log.d("TMDB_API", "getMovieGenres: response=$data")
            
            val parsed = tryParseJson<GenreResponse>(data)
            val genreMap = parsed?.genres?.associate {
                (it.id?.toString() ?: "") to (it.name ?: "")
            }?.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            
            movieGenreCache = genreMap
            genreMap
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API", "getMovieGenres: ERROR", e)
            null
        }
    }
    
    /**
     * Fetch TV genres from TMDB.
     * Returns map of ID (as string) to name.
     */
    suspend fun getTvGenres(apiKey: String): Map<String, String>? {
        // Return cached if available
        tvGenreCache?.let { return it }
        
        return try {
            val url = "$BASE_URL/genre/tv/list?api_key=$apiKey"
            val response = app.get(url, timeout = 10000)
            val data = response.text
            
            android.util.Log.d("TMDB_API", "getTvGenres: url=$url")
            android.util.Log.d("TMDB_API", "getTvGenres: response=$data")
            
            val parsed = tryParseJson<GenreResponse>(data)
            val genreMap = parsed?.genres?.associate {
                (it.id?.toString() ?: "") to (it.name ?: "")
            }?.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            
            tvGenreCache = genreMap
            genreMap
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API", "getTvGenres: ERROR", e)
            null
        }
    }
    
    /**
     * Clear genre caches (useful for refresh)
     */
    fun clearGenreCaches() {
        movieGenreCache = null
        tvGenreCache = null
    }
    
    /**
     * Get cached genres for a specific format
     */
    fun getCachedGenres(isMovie: Boolean): Map<String, String>? {
        return if (isMovie) movieGenreCache else tvGenreCache
    }
    
    /**
     * Discover movies with full filter support
     */
    suspend fun discoverMovies(
        context: android.content.Context,
        apiKey: String,
        genres: List<String> = emptyList(),
        excludedGenres: List<String> = emptyList(),
        keywords: String = "",
        minVotes: Int = 0,
        year: String? = null,
        country: String? = null,
        provider: String? = null,
        sort: String = "popularity.desc",
        includeAdult: Boolean = false,
        page: Int = 1
    ): List<BrowseMediaItem>? {
        val displayLanguage = getDisplayLanguage(context)
        android.util.Log.d("TMDB_API_DEBUG", "========== discoverMovies START ==========")
        android.util.Log.d("TMDB_API_DEBUG", "Params: apiKey=$apiKey, page=$page, language=$displayLanguage")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: genres=$genres, excluded=$excludedGenres")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: year=$year, country=$country, provider=$provider")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: sort=$sort, includeAdult=$includeAdult, minVotes=$minVotes")
        
        return try {
            val params = mutableListOf("api_key=$apiKey", "page=$page")
            
            // Include genres (OR logic with |, AND with ,)
            if (genres.isNotEmpty()) {
                params.add("with_genres=${genres.joinToString(",")}")
                android.util.Log.d("TMDB_API_DEBUG", "Added with_genres=${genres.joinToString(",")}")
            }
            
            // Exclude genres
            if (excludedGenres.isNotEmpty()) {
                params.add("without_genres=${excludedGenres.joinToString(",")}")
                android.util.Log.d("TMDB_API_DEBUG", "Added without_genres=${excludedGenres.joinToString(",")}")
            }
            
            // Year filter (primary_release_year for movies)
            year?.takeIf { it != "All" }?.toIntOrNull()?.let {
                params.add("primary_release_year=$it")
                android.util.Log.d("TMDB_API_DEBUG", "Added primary_release_year=$it")
            }
            
            // Country filter (with_origin_country)
            country?.takeIf { it != "All" }?.let {
                params.add("with_origin_country=$it")
                android.util.Log.d("TMDB_API_DEBUG", "Added with_origin_country=$it")
            }
            
            // Keywords filter - translate to IDs
            if (keywords.isNotBlank()) {
                // Split keywords by comma and translate each to ID
                val keywordTexts = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val keywordIds = mutableListOf<String>()
                
                for (keywordText in keywordTexts) {
                    // Try to parse as number first (if already an ID)
                    val keywordId = if (keywordText.all { it.isDigit() }) {
                        keywordText
                    } else {
                        // Search for keyword ID
                        searchKeyword(context, apiKey, keywordText)
                    }
                    
                    if (keywordId != null) {
                        keywordIds.add(keywordId)
                    }
                }
                
                if (keywordIds.isNotEmpty()) {
                    params.add("with_keywords=${keywordIds.joinToString(",")}")
                    android.util.Log.d("TMDB_API_DEBUG", "Added with_keywords=${keywordIds.joinToString(",")} (translated from: $keywords)")
                }
            }
            
            // Minimum votes filter
            if (minVotes > 0) {
                params.add("vote_count.gte=$minVotes")
                android.util.Log.d("TMDB_API_DEBUG", "Added vote_count.gte=$minVotes")
            }
            
            // Watch provider filter using buildTmdbQuery helper for region logic
            val selectedProviderIds = provider?.takeIf { it != "All" }?.let { listOf(it) } ?: emptyList()
            val regionQuery = buildTmdbQuery(selectedProviderIds, country ?: "US")
            
            regionQuery.forEach { (key, value) ->
                params.add("$key=$value")
            }
            android.util.Log.d("TMDB_API_DEBUG", "Added region query: $regionQuery")
            
            // Sort - handle format-specific date parameters
            val sortParam = when {
                sort.contains("Release Date") -> {
                    // Use format-specific date parameter as per hsp1020's feedback
                    "primary_release_date.desc"
                }
                else -> sort
            }
            params.add("sort_by=$sortParam")
            
            // Adult content
            params.add("include_adult=$includeAdult")
            
            // Display language
            params.add("language=$displayLanguage")
            
            val url = "$BASE_URL/discover/movie?${params.joinToString("&")}"
            android.util.Log.d("TMDB_API_DEBUG", "Final URL: $url")
            
            val response = app.get(url, timeout = 10000)
            val data = response.text
            android.util.Log.d("TMDB_API_DEBUG", "Response received, length=${data.length}")
            
            val parsed = tryParseJson<DiscoverResponse>(data)
            val results = parsed?.results?.mapNotNull { it.toBrowseMediaItem(isMovie = true) }
            android.util.Log.d("TMDB_API_DEBUG", "Parsed ${results?.size ?: 0} results")
            android.util.Log.d("TMDB_API_DEBUG", "========== discoverMovies END ==========")
            results
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API_DEBUG", "discoverMovies: ERROR", e)
            android.util.Log.d("TMDB_API_DEBUG", "========== discoverMovies END (ERROR) ==========")
            null
        }
    }
    
    /**
     * Discover TV shows with full filter support
     */
    suspend fun discoverTv(
        context: android.content.Context,
        apiKey: String,
        genres: List<String> = emptyList(),
        excludedGenres: List<String> = emptyList(),
        keywords: String = "",
        minVotes: Int = 0,
        year: String? = null,
        country: String? = null,
        provider: String? = null,
        sort: String = "popularity.desc",
        includeAdult: Boolean = false,
        page: Int = 1
    ): List<BrowseMediaItem>? {
        val displayLanguage = getDisplayLanguage(context)
        android.util.Log.d("TMDB_API_DEBUG", "========== discoverTv START ==========")
        android.util.Log.d("TMDB_API_DEBUG", "Params: apiKey=$apiKey, page=$page, language=$displayLanguage")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: genres=$genres, excluded=$excludedGenres")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: year=$year, country=$country, provider=$provider")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: sort=$sort, includeAdult=$includeAdult")
        
        return try {
            val params = mutableListOf("api_key=$apiKey", "page=$page")
            
            // Include genres
            if (genres.isNotEmpty()) {
                params.add("with_genres=${genres.joinToString(",")}")
            }
            
            // Exclude genres
            if (excludedGenres.isNotEmpty()) {
                params.add("without_genres=${excludedGenres.joinToString(",")}")
            }
            
            // Year filter (first_air_date_year for TV)
            year?.takeIf { it != "All" }?.toIntOrNull()?.let {
                params.add("first_air_date_year=$it")
            }
            
            // Watch provider filter using buildTmdbQuery helper for region logic
            val selectedProviderIds = provider?.takeIf { it != "All" }?.let { listOf(it) } ?: emptyList()
            val regionQuery = buildTmdbQuery(selectedProviderIds, country ?: "US")
            
            regionQuery.forEach { (key, value) ->
                params.add("$key=$value")
            }
            android.util.Log.d("TMDB_API_DEBUG", "Added region query: $regionQuery")
            
            // Sort - handle format-specific date parameters
            val sortParam = when {
                sort.contains("Release Date") -> {
                    // Use format-specific date parameter as per hsp1020's feedback
                    "first_air_date.desc"
                }
                else -> sort
            }
            params.add("sort_by=$sortParam")
            
            // Adult content
            params.add("include_adult=$includeAdult")
            
            // Display language
            params.add("language=$displayLanguage")
            
            val url = "$BASE_URL/discover/tv?${params.joinToString("&")}"
            android.util.Log.d("TMDB_API_DEBUG", "Final URL: $url")
            
            val response = app.get(url, timeout = 10000)
            val data = response.text
            android.util.Log.d("TMDB_API_DEBUG", "Response received, length=${data.length}")
            
            val parsed = tryParseJson<DiscoverResponse>(data)
            val results = parsed?.results?.mapNotNull { it.toBrowseMediaItem(isMovie = false) }
            android.util.Log.d("TMDB_API_DEBUG", "Parsed ${results?.size ?: 0} results")
            android.util.Log.d("TMDB_API_DEBUG", "========== discoverTv END ==========")
            results
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API_DEBUG", "discoverTv: ERROR", e)
            android.util.Log.d("TMDB_API_DEBUG", "========== discoverTv END (ERROR) ==========")
            null
        }
    }
    
    /**
     * Get trending content (short-term popularity spikes)
     */
    suspend fun getTrending(
        context: android.content.Context,
        apiKey: String,
        mediaType: String = "all",  // "all", "movie", "tv"
        timeWindow: String = "week",  // "day", "week"
        page: Int = 1
    ): List<BrowseMediaItem>? {
        val displayLanguage = getDisplayLanguage(context)
        android.util.Log.d("TMDB_API_DEBUG", "========== getTrending START ==========")
        android.util.Log.d("TMDB_API_DEBUG", "Params: apiKey=$apiKey, page=$page, language=$displayLanguage")
        android.util.Log.d("TMDB_API_DEBUG", "Filters: mediaType=$mediaType, timeWindow=$timeWindow")
        
        return try {
            val url = "$BASE_URL/trending/$mediaType/$timeWindow?api_key=$apiKey&page=$page&language=$displayLanguage"
            android.util.Log.d("TMDB_API_DEBUG", "Final URL: $url")
            
            val response = app.get(url, timeout = 10000)
            val data = response.text
            android.util.Log.d("TMDB_API_DEBUG", "Response received, length=${data.length}")
            
            val parsed = tryParseJson<DiscoverResponse>(data)
            val results = parsed?.results?.mapNotNull { 
                val isMovie = it.mediaType == "movie" || (mediaType == "movie" && it.title != null)
                it.toBrowseMediaItem(isMovie) 
            }
            android.util.Log.d("TMDB_API_DEBUG", "Parsed ${results?.size ?: 0} results")
            android.util.Log.d("TMDB_API_DEBUG", "========== getTrending END ==========")
            results
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API_DEBUG", "getTrending: ERROR", e)
            android.util.Log.d("TMDB_API_DEBUG", "========== getTrending END (ERROR) ==========")
            null
        }
    }

    /**
     * Search for movies, TV shows, and people using Multi-Search endpoint
     */
    suspend fun searchMulti(
        context: android.content.Context,
        apiKey: String,
        query: String,
        page: Int = 1,
        includeAdult: Boolean = false
    ): List<BrowseMediaItem>? {
        val displayLanguage = getDisplayLanguage(context)
        android.util.Log.d("TMDB_API_DEBUG", "========== searchMulti START ==========")
        android.util.Log.d("TMDB_API_DEBUG", "searchMulti: query='$query', page=$page, includeAdult=$includeAdult")
        
        return try {
            val params = mutableListOf(
                "api_key=$apiKey",
                "query=$query",
                "language=$displayLanguage",
                "page=$page",
                "include_adult=$includeAdult"
            )
            
            val url = "$BASE_URL/search/multi?${params.joinToString("&")}"
            android.util.Log.d("TMDB_API_DEBUG", "searchMulti: URL=$url")
            
            val response = app.get(url, timeout = 10000)
            val data = response.text
            android.util.Log.d("TMDB_API_DEBUG", "searchMulti: Response received, length=${data.length}")
            
            val searchResponse = tryParseJson<SearchMultiResponse>(data)
            val results = searchResponse?.results?.mapNotNull { result ->
                // Filter out people results, only keep movies and TV shows
                if (result.mediaType == "person") {
                    null
                } else {
                    result.toBrowseMediaItem()
                }
            }
            
            android.util.Log.d("TMDB_API_DEBUG", "Parsed ${results?.size ?: 0} results")
            android.util.Log.d("TMDB_API_DEBUG", "========== searchMulti END ==========")
            results
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API_DEBUG", "searchMulti: ERROR", e)
            android.util.Log.d("TMDB_API_DEBUG", "========== searchMulti END (ERROR) ==========")
            null
        }
    }

    /**
     * Search for keyword ID by text
     */
    suspend fun searchKeyword(
        context: android.content.Context,
        apiKey: String,
        query: String
    ): String? {
        val displayLanguage = getDisplayLanguage(context)
        android.util.Log.d("TMDB_API_DEBUG", "========== searchKeyword START ==========")
        android.util.Log.d("TMDB_API_DEBUG", "searchKeyword: query='$query', language=$displayLanguage")
        
        return try {
            val url = "$BASE_URL/search/keyword?api_key=$apiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=$displayLanguage"
            android.util.Log.d("TMDB_API_DEBUG", "searchKeyword: URL=$url")
            
            val response = app.get(url, timeout = 10000)
            val data = response.text
            android.util.Log.d("TMDB_API_DEBUG", "searchKeyword: Response received, length=${data.length}")
            
            // Parse keyword search response to get first keyword ID
            val parsed = tryParseJson<KeywordSearchResponse>(data)
            val keywordId = parsed?.results?.firstOrNull()?.id
            android.util.Log.d("TMDB_API_DEBUG", "searchKeyword: Found keyword ID: $keywordId for query: '$query'")
            android.util.Log.d("TMDB_API_DEBUG", "========== searchKeyword END ==========")
            keywordId?.toString()
        } catch (e: Exception) {
            android.util.Log.e("TMDB_API_DEBUG", "searchKeyword: ERROR", e)
            android.util.Log.d("TMDB_API_DEBUG", "========== searchKeyword END (ERROR) ==========")
            null
        }
    }

    /**
     * Search for keywords by text - returns list of keyword results
     */
    suspend fun searchKeywords(apiKey: String, query: String): List<KeywordResult>? {
        return try {
            if (query.isBlank()) return emptyList()
            
            val url = "$BASE_URL/search/keyword?api_key=$apiKey&query=${URLEncoder.encode(query, "UTF-8")}&page=1"
            android.util.Log.d("KEYWORD_SEARCH_DEBUG", "Searching keywords: $query")
            android.util.Log.d("KEYWORD_SEARCH_DEBUG", "URL: $url")
            
            val response = app.get(url, timeout = 5000)
            val data = response.text
            
            val parsed = tryParseJson<KeywordSearchResponse>(data)
            val results = parsed?.results?.map { 
                KeywordResult(it.id ?: 0, it.name ?: "") 
            }?.filter { it.name.isNotBlank() && it.id > 0 }
            
            android.util.Log.d("KEYWORD_SEARCH_DEBUG", "Found ${results?.size ?: 0} keywords")
            results
        } catch (e: Exception) {
            android.util.Log.e("KEYWORD_SEARCH_DEBUG", "Error searching keywords", e)
            null
        }
    }

    /**
     * Convert TMDB SearchMultiResult to BrowseMediaItem
     */
    private fun SearchMultiResult.toBrowseMediaItem(): BrowseMediaItem? {
        val resultId = id ?: return null
        val resultTitle = title ?: name ?: return null
        val resultMediaType = mediaType ?: return null
        
        // Only process movie and TV results, skip people
        if (resultMediaType == "person") return null
        
        val isMovie = resultMediaType == "movie"
        
        return BrowseMediaItem(
            id = "tmdb_$resultId",
            title = resultTitle,
            posterUrl = posterPath?.let { "$IMAGE_BASE_URL$it" },
            type = BrowseMediaType.fromTmdbMediaType(resultMediaType, isMovie),
            provider = FilterProvider.TMDB,
            sourceData = this
        )
    }

    /**
     * Convert TMDB DiscoverResult to BrowseMediaItem
     */
    private fun DiscoverResult.toBrowseMediaItem(isMovie: Boolean): BrowseMediaItem? {
        val resultId = id ?: return null
        val resultTitle = title ?: name ?: return null
        
        return BrowseMediaItem(
            id = "tmdb_$resultId",
            title = resultTitle,
            posterUrl = posterPath?.let { "$IMAGE_BASE_URL$it" },
            type = BrowseMediaType.fromTmdbMediaType(mediaType, isMovie),
            provider = FilterProvider.TMDB,
            sourceData = this
        )
    }
}
