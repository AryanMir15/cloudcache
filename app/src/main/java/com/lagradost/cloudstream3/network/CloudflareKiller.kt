package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.Collections


@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=", limit = 2)
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }

        private fun Response.isCloudflareChallenge(): Boolean =
            code in ERROR_CODES && header("Server") in CLOUDFLARE_SERVERS
    }

    // Deliberately no CookieManager.removeAllCookies() here anymore.
    // CookieManager persists cf_clearance across instances and app restarts,
    // so a solved challenge is reused instead of cleared on every provider init.
    // Expired cookies are handled by re-solving below.

    val savedCookies: MutableMap<String, Map<String, String>> =
        Collections.synchronizedMap(mutableMapOf())

    /**
     * Gets the headers with cookies, webview user agent included!
     * */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val host = request.url.host

        // Reuse a clearance cookie that survives app restarts before hitting the network.
        if (savedCookies[host] == null) {
            trySolveWithSavedCookies(request)
        }

        val cachedCookies = savedCookies[host]
        val response = if (cachedCookies != null) {
            proceed(request, cachedCookies)
        } else {
            chain.proceed(request)
        }

        if (!response.isCloudflareChallenge()) {
            return@runBlocking response
        }

        // Either there was no cookie, or Cloudflare rejected an expired one.
        response.close()
        if (cachedCookies != null) {
            Log.d(TAG, "Cached cloudflare cookies rejected for $host, solving again")
            savedCookies.remove(host)
        }

        bypassCloudflare(request, forceWebView = cachedCookies != null)?.let {
            if (!it.isCloudflareChallenge()) {
                Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                return@runBlocking it
            }
            it.close()
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return safe {
            CookieManager.getInstance()?.getCookie(url)
        }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager
     * Also saves the cookies.
     * */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        // Not sure if this takes expiration into account
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers =
            getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(
        request: Request,
        forceWebView: Boolean = false
    ): Response? {
        val url = request.url.toString()

        // If no cookies then try to get them. When Cloudflare rejects a cached
        // clearance cookie we must not trust the (stale) copy in CookieManager,
        // so force the WebView to solve the challenge fresh.
        if (forceWebView || !trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
            WebViewResolver(
                // Never exit based on url
                Regex(".^"),
                // Cloudflare needs default user agent
                userAgent = null,
                // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
                useOkhttp = false,
                // Match every url for the requestCallBack
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(
                url
            ) {
                trySolveWithSavedCookies(request)
            }
            // Make sure the freshly solved cookies are written to disk.
            safe { CookieManager.getInstance().flush() }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}
