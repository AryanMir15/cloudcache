package com.lagradost.cloudstream3.utils

import android.util.Log
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse

/**
 * Utility class to resolve session URLs to HTTP URLs
 * This fixes the issue where providers like AnimePahe return session URLs
 * that cannot be opened directly in browsers
 */
object SessionUrlResolver {
    private const val TAG = "SESSION_URL_RESOLVER"
    
    /**
     * Resolves session URL to HTTP URL by calling the API provider
     * @param sessionUrl The session URL to resolve
     * @param apiName The API provider name (e.g., "AnimePahe")
     * @return HTTP URL if resolution succeeds, null otherwise
     */
    suspend fun resolveSessionToHttp(sessionUrl: String, apiName: String?): String? {
        Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_START ===")
        Log.d(TAG, "Input sessionUrl: $sessionUrl")
        Log.d(TAG, "Input apiName: $apiName")
        
        // Check if already HTTP URL
        if (sessionUrl.startsWith("http")) {
            Log.d(TAG, "ALREADY_HTTP_URL - URL is already HTTP, returning as-is")
            Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_END ===")
            return sessionUrl
        }
        
        // Check if it's a session URL
        val isSessionUrl = sessionUrl.contains("session") && sessionUrl.contains("sessionDate")
        if (!isSessionUrl) {
            Log.w(TAG, "NOT_SESSION_URL - URL is not a session URL, returning null")
            Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_END ===")
            return null
        }
        
        // Extract session ID
        val sessionIdMatch = Regex("\"session\":\"([^\"]+)\"").find(sessionUrl)
        val sessionId = sessionIdMatch?.groupValues?.get(1)
        
        if (sessionId == null) {
            Log.e(TAG, "EXTRACTION_FAILED - Could not extract session ID from: $sessionUrl")
            Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_END ===")
            return null
        }
        
        Log.d(TAG, "EXTRACTED_SESSION_ID - sessionId: $sessionId")
        
        // Try to resolve using the API provider
        val resolvedUrl = tryResolveWithProvider(sessionUrl, apiName, sessionId)
        
        if (resolvedUrl != null) {
            Log.d(TAG, "✓ RESOLUTION_SUCCESS - Resolved to HTTP URL: $resolvedUrl")
            Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_END ===")
            return resolvedUrl
        } else {
            Log.w(TAG, "✗ RESOLUTION_FAILED - Could not resolve session URL to HTTP URL")
            Log.d(TAG, "=== RESOLVE_SESSION_TO_HTTP_END ===")
            return null
        }
    }
    
    /**
     * Attempts to resolve session URL using the appropriate API provider
     * @param sessionUrl The original session URL
     * @param apiName The API provider name
     * @param sessionId The extracted session ID
     * @return HTTP URL if resolution succeeds, null otherwise
     */
    private suspend fun tryResolveWithProvider(sessionUrl: String, apiName: String?, sessionId: String): String? {
        Log.d(TAG, "=== TRY_RESOLVE_WITH_PROVIDER_START ===")
        Log.d(TAG, "Attempting resolution with provider: $apiName")
        
        return try {
            when (apiName?.lowercase()) {
                "animepahe" -> resolveAnimePaheSession(sessionUrl, sessionId)
                else -> {
                    Log.w(TAG, "UNSUPPORTED_PROVIDER - Provider '$apiName' not supported for session resolution")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PROVIDER_RESOLUTION_ERROR - Error resolving with provider '$apiName'", e)
            null
        } finally {
            Log.d(TAG, "=== TRY_RESOLVE_WITH_PROVIDER_END ===")
        }
    }
    
    /**
     * Resolves AnimePahe session URL to HTTP URL
     * @param sessionUrl The original session URL
     * @param sessionId The extracted session ID
     * @return HTTP URL if resolution succeeds, null otherwise
     */
    private suspend fun resolveAnimePaheSession(sessionUrl: String, sessionId: String): String? {
        Log.d(TAG, "=== RESOLVE_ANIMEPAHE_SESSION_START ===")
        Log.d(TAG, "Attempting to resolve AnimePahe session: $sessionId")
        
        return try {
            // Parse the session URL to extract the anime information
            val sessionData = parseSessionUrl(sessionUrl)
            if (sessionData == null) {
                Log.e(TAG, "SESSION_PARSE_FAILED - Could not parse session URL data")
                return null
            }
            
            Log.d(TAG, "PARSED_SESSION_DATA - name: ${sessionData.name}")
            
            // Try to search for the anime by name to get the HTTP URL
            val httpUrl = searchAnimePaheByName(sessionData.name)
            
            if (httpUrl != null) {
                Log.d(TAG, "✓ ANIMEPAHE_RESOLUTION_SUCCESS - Found HTTP URL: $httpUrl")
                return httpUrl
            } else {
                Log.w(TAG, "✗ ANIMEPAHE_RESOLUTION_FAILED - Could not find HTTP URL for: ${sessionData.name}")
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "ANIMEPAHE_RESOLUTION_ERROR - Error resolving AnimePahe session", e)
            null
        } finally {
            Log.d(TAG, "=== RESOLVE_ANIMEPAHE_SESSION_END ===")
        }
    }
    
    /**
     * Parses session URL to extract anime name and other data
     * @param sessionUrl The session URL to parse
     * @return Parsed session data or null if parsing fails
     */
    private fun parseSessionUrl(sessionUrl: String): SessionData? {
        Log.d(TAG, "=== PARSE_SESSION_URL_START ===")
        Log.d(TAG, "Parsing session URL: $sessionUrl")
        
        return try {
            // Extract JSON data from session URL
            val jsonMatch = Regex("\\{.*\\}").find(sessionUrl)
            val jsonString = jsonMatch?.value
            
            if (jsonString == null) {
                Log.e(TAG, "JSON_EXTRACTION_FAILED - Could not extract JSON from session URL")
                return null
            }
            
            Log.d(TAG, "EXTRACTED_JSON - $jsonString")
            
            // Parse the JSON to extract the name
            val nameMatch = Regex("\"name\":\"([^\"]+)\"").find(jsonString)
            val name = nameMatch?.groupValues?.get(1)
            
            if (name == null) {
                Log.e(TAG, "NAME_EXTRACTION_FAILED - Could not extract name from session JSON")
                return null
            }
            
            Log.d(TAG, "✓ SESSION_PARSE_SUCCESS - Extracted name: $name")
            Log.d(TAG, "=== PARSE_SESSION_URL_END ===")
            
            SessionData(name = name)
            
        } catch (e: Exception) {
            Log.e(TAG, "SESSION_PARSE_ERROR - Error parsing session URL", e)
            Log.d(TAG, "=== PARSE_SESSION_URL_END ===")
            null
        }
    }
    
    /**
     * Searches for anime by name on AnimePahe to get HTTP URL
     * @param animeName The anime name to search for
     * @return HTTP URL if found, null otherwise
     */
    private suspend fun searchAnimePaheByName(animeName: String): String? {
        Log.d(TAG, "=== SEARCH_ANIMEPAHE_BY_NAME_START ===")
        Log.d(TAG, "Searching AnimePahe for: $animeName")
        
        return try {
            // Use the AnimePahe API to search by name
            val api = APIHolder.getApiFromNameNull("AnimePahe") ?: run {
                Log.e(TAG, "ANIMEPAHE_API_NOT_FOUND - AnimePahe API not available")
                return null
            }
            
            Log.d(TAG, "ANIMEPAHE_API_FOUND - Using AnimePahe API for search")
            
            // Search for the anime by name
            val searchResponse = api.search(animeName)
            val searchResults = searchResponse ?: emptyList()
            Log.d(TAG, "ANIMEPAHE_SEARCH_RESULTS - Found ${searchResults.size} results")
            
            // Find the best matching result by name
            val bestMatch = searchResults.find { result: SearchResponse ->
                val resultName = result.name.lowercase().trim()
                val searchName = animeName.lowercase().trim()
                
                // Exact match
                resultName == searchName ||
                // Contains match (handle cases where search name is part of result name)
                resultName.contains(searchName) ||
                searchName.contains(resultName)
            }
            
            if (bestMatch != null) {
                Log.d(TAG, "✓ ANIMEPAHE_MATCH_FOUND - Best match: ${bestMatch.name}")
                Log.d(TAG, "✓ ANIMEPAHE_HTTP_URL - Resolved URL: ${bestMatch.url}")
                
                // Verify it's actually an HTTP URL
                if (bestMatch.url.startsWith("http")) {
                    Log.d(TAG, "✓ ANIMEPAHE_VALID_HTTP_URL - URL is valid HTTP URL")
                    Log.d(TAG, "=== SEARCH_ANIMEPAHE_BY_NAME_END ===")
                    return bestMatch.url
                } else {
                    Log.w(TAG, "✗ ANIMEPAHE_INVALID_HTTP_URL - URL is not HTTP: ${bestMatch.url}")
                }
            } else {
                Log.w(TAG, "✗ ANIMEPAHE_NO_MATCH - No matching result found for: $animeName")
                
                // Log all available results for debugging
                searchResults.take(3).forEachIndexed { index: Int, result: SearchResponse ->
                    Log.d(TAG, "ANIMEPAHE_AVAILABLE_RESULT_$index - ${result.name}: ${result.url}")
                }
            }
            
            Log.d(TAG, "=== SEARCH_ANIMEPAHE_BY_NAME_END ===")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "ANIMEPAHE_SEARCH_ERROR - Error searching AnimePahe", e)
            Log.d(TAG, "=== SEARCH_ANIMEPAHE_BY_NAME_END ===")
            null
        }
    }
    
    /**
     * Data class to hold parsed session information
     */
    private data class SessionData(
        val name: String
    )
}
