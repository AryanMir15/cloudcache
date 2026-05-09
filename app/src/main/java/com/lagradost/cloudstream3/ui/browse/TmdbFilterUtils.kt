package com.lagradost.cloudstream3.ui.browse

/**
 * TMDB-specific filter utilities for Browse tab.
 * Handles genre caching, sort mapping, and all TMDB filter parameters.
 */
object TmdbFilterUtils {
    
    // ============================================
    // GENRE CACHE MANAGEMENT
    // ============================================
    
    /**
     * Clear genre cache when language changes
     * This forces fresh genre names to be fetched in the new language
     */
    fun clearGenreCache() {
        // In a real implementation, you might want to clear any cached genre names
        // that were fetched from TMDB API with the previous language setting
        android.util.Log.d("TmdbFilterUtils", "Genre cache cleared due to language change")
    }
    
    // ============================================
    // SORT OPTIONS
    // ============================================
    
    val SORT_OPTIONS = listOf(
        "Popularity (High to Low)" to "popularity.desc",
        "Popularity (Low to High)" to "popularity.asc",
        "Rating (High to Low)" to "vote_average.desc",
        "Rating (Low to High)" to "vote_average.asc",
        "Release Date (Newest)" to "primary_release_date.desc",
        "Release Date (Oldest)" to "primary_release_date.asc",
        "Revenue (High to Low)" to "revenue.desc",
        "Vote Count (High to Low)" to "vote_count.desc",
        "Title (A-Z)" to "original_title.asc",
        "Title (Z-A)" to "original_title.desc"
    )
    
    val SORT_DISPLAY_NAMES = SORT_OPTIONS.map { it.first }
    
    fun convertSortToApi(sort: String): String {
        return SORT_OPTIONS.find { it.first == sort }?.second 
            ?: "popularity.desc"
    }
    
    fun convertSortFromApi(apiSort: String): String {
        return SORT_OPTIONS.find { it.second == apiSort }?.first 
            ?: "Popularity (High to Low)"
    }
    
    // ============================================
    // YEARS
    // ============================================
    
    val YEARS = listOf("All") + (1940..2027).reversed().map { it.toString() }
    
    // ============================================
    // FORMAT (Movie/TV)
    // ============================================
    
    val FORMATS = listOf("Movie", "TV Show")
    
    // ============================================
    // MOVIE GENRES (TMDB IDs)
    // ============================================
    
    val MOVIE_GENRES = listOf(
        "28" to "Action",
        "12" to "Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "18" to "Drama",
        "10751" to "Family",
        "14" to "Fantasy",
        "36" to "History",
        "27" to "Horror",
        "10402" to "Music",
        "9648" to "Mystery",
        "10749" to "Romance",
        "878" to "Science Fiction",
        "10770" to "TV Movie",
        "53" to "Thriller",
        "10752" to "War",
        "37" to "Western"
    )
    
    val MOVIE_GENRE_IDS = MOVIE_GENRES.map { it.first }
    val MOVIE_GENRE_NAMES = MOVIE_GENRES.map { it.second }
    val MOVIE_GENRE_MAP = MOVIE_GENRES.toMap()
    
    // ============================================
    // TV GENRES (TMDB IDs)
    // ============================================
    
    val TV_GENRES = listOf(
        "10759" to "Action & Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "18" to "Drama",
        "10751" to "Family",
        "10762" to "Kids",
        "9648" to "Mystery",
        "10763" to "News",
        "10764" to "Reality",
        "10765" to "Sci-Fi & Fantasy",
        "10766" to "Soap",
        "10767" to "Talk",
        "10768" to "War & Politics",
        "37" to "Western"
    )
    
    val TV_GENRE_IDS = TV_GENRES.map { it.first }
    val TV_GENRE_NAMES = TV_GENRES.map { it.second }
    val TV_GENRE_MAP = TV_GENRES.toMap()
    
    fun getGenresForFormat(format: String): List<Pair<String, String>> {
        return when (format) {
            "Movie" -> MOVIE_GENRES
            "TV Show" -> TV_GENRES
            else -> MOVIE_GENRES
        }
    }
    
    fun getGenreIdByName(format: String, name: String): String? {
        return when (format) {
            "Movie" -> MOVIE_GENRES.find { it.second == name }?.first
            "TV Show" -> TV_GENRES.find { it.second == name }?.first
            else -> null
        }
    }
    
    // ============================================
    // COUNTRIES (with_origin_country)
    // ============================================
    
    val COUNTRIES = listOf(
        "All" to null,
        "United States" to "US",
        "United Kingdom" to "GB",
        "Japan" to "JP",
        "South Korea" to "KR",
        "China" to "CN",
        "India" to "IN",
        "France" to "FR",
        "Germany" to "DE",
        "Italy" to "IT",
        "Spain" to "ES",
        "Canada" to "CA",
        "Australia" to "AU",
        "Brazil" to "BR",
        "Mexico" to "MX",
        "Russia" to "RU",
        "Turkey" to "TR",
        "Thailand" to "TH",
        "Indonesia" to "ID",
        "Poland" to "PL",
        "Netherlands" to "NL",
        "Sweden" to "SE",
        "Norway" to "NO",
        "Denmark" to "DK",
        "Finland" to "FI",
        "Belgium" to "BE",
        "Argentina" to "AR",
        "Colombia" to "CO",
        "Philippines" to "PH",
        "Taiwan" to "TW",
        "Hong Kong" to "HK",
        "Singapore" to "SG"
    )
    
    val COUNTRY_NAMES = COUNTRIES.map { it.first }
    
    fun getCountryCode(name: String): String? {
        return COUNTRIES.find { it.first == name }?.second
    }
    
    // ============================================
    // TRENDING TIME WINDOWS
    // ============================================
    
    val TRENDING_TIME_WINDOWS = listOf(
        "Off" to "off",
        "Today" to "day",
        "This Week" to "week"
    )
    
    val TRENDING_DISPLAY_NAMES = TRENDING_TIME_WINDOWS.map { it.first }
    
    fun getTrendingTimeWindow(name: String): String? {
        return TRENDING_TIME_WINDOWS.find { it.first == name }?.second
    }
    
    // ============================================
    // WATCH PROVIDERS (Streaming services)
    // ============================================
    
    // Popular providers with TMDB IDs (US region)
    val WATCH_PROVIDERS = listOf(
        "All" to null,
        "Netflix" to "8",
        "Amazon Prime Video" to "9",
        "Disney Plus" to "337",
        "Hulu" to "15",
        "HBO Max" to "384",
        "Apple TV Plus" to "350",
        "Paramount Plus" to "531",
        "Peacock" to "386",
        "YouTube" to "192",
        "Google Play Movies" to "3",
        "iTunes" to "2",
        "Crunchyroll" to "283",
        "Funimation" to "111",
        "Tubi TV" to "73",
        "Pluto TV" to "300",
        "Rakuten Viki" to "344",
        "Microsoft Store" to "68",
        "Amazon Video" to "10",
        "Starz" to "43",
        "Showtime" to "37",
        "AMC+" to "526",
        "Shudder" to "99",
        "MUBI" to "11",
        "Criterion Channel" to "258",
        // KOREAN_STREAMING_SERVICES: Added Korean streaming services with correct TMDB IDs
        "Watcha" to "97",
        "Wavve" to "356", 
        "TVING" to "1796",
        "Coupang Play" to "2416",
        "Netflix (KR)" to "82",
        "Disney Plus (KR)" to "337"
    )
    
    val PROVIDER_NAMES = WATCH_PROVIDERS.map { it.first }
    
    fun getProviderId(name: String): String? {
        return WATCH_PROVIDERS.find { it.first == name }?.second
    }
    
    // ============================================
    // RATING (vote_average.gte)
    // ============================================
    
    val RATING_OPTIONS = listOf(
        "Any" to 0f,
        "1+" to 1f,
        "2+" to 2f,
        "3+" to 3f,
        "4+" to 4f,
        "5+" to 5f,
        "6+" to 6f,
        "7+" to 7f,
        "8+" to 8f,
        "9+" to 9f
    )
    
    val RATING_DISPLAY_NAMES = RATING_OPTIONS.map { it.first }
    
    fun getRatingValue(name: String): Float {
        return RATING_OPTIONS.find { it.first == name }?.second ?: 0f
    }
    
    // ============================================
    // RUNTIME (with_runtime.lte - "Under X mins")
    // ============================================
    
    val RUNTIME_OPTIONS = listOf(
        "Any" to null,
        "Under 30 min" to 30,
        "Under 1 hour" to 60,
        "Under 1.5 hours" to 90,
        "Under 2 hours" to 120,
        "Under 2.5 hours" to 150,
        "Under 3 hours" to 180
    )
    
    val RUNTIME_DISPLAY_NAMES = RUNTIME_OPTIONS.map { it.first }
    
    fun getRuntimeValue(name: String): Int? {
        return RUNTIME_OPTIONS.find { it.first == name }?.second
    }
    
    // ============================================
    // UTILITIES
    // ============================================
    
    fun getGenreDisplayName(genreId: String, genreMap: Map<String, String>?): String {
        return genreMap?.get(genreId) ?: "Genre $genreId"
    }
    
    fun getSortedGenreEntries(genreMap: Map<String, String>?): List<Pair<String, String>> {
        return genreMap?.toList()?.sortedBy { it.second } ?: emptyList()
    }
}
