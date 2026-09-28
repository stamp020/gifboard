package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Legacy GIF provider that fetches results via Google's undocumented JSON API.
 * Uses a direct OkHttp request with `async=ijn:<page>,_fmt:json` to get
 * structured JSON responses. Lightweight but may not always be available.
 *
 * Google Image Search has no "trending" feed, so [getTrending] falls back to
 * a generic query.
 */
class JsonApiGifProvider(private val safeSearch: String = "active") : PagedGifProvider() {

    companion object {
        private const val BASE_URL = "https://www.google.com/search"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private const val MAX_FILE_SIZE_MB = 10.0f
        private const val TRENDING_QUERY = "trending gif"
    }

    private val client = NetworkClients.shared

    override fun getName(): String = "Google (Legacy)"

    override fun supportsAdultContent(): Boolean = false

    override suspend fun getTrending(limit: Int): List<GifResult> = search(TRENDING_QUERY, limit)

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "q" to "$query gif",
            "tbm" to "isch",
            "tbs" to "itp:animated",
            "client" to "chrome",
            "safe" to safeSearch,
            "asearch" to "isch",
            "async" to "ijn:$page,_fmt:json"
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val httpRequest = Request.Builder()
            .url("$BASE_URL?$queryString")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            var content = response.body?.string() ?: ""

            // Strip Google's XSSI protection prefix
            if (content.startsWith(")]}'")) {
                content = content.substring(4).trim()
            }

            parseJsonResults(content)
        }
    }

    private fun parseJsonResults(jsonResponse: String): List<GifResult> {
        val items = mutableListOf<GifResult>()
        try {
            val json = JSONObject(jsonResponse)
            val ischj = json.optJSONObject("ischj") ?: return items
            val resultsStr = ischj.optString("results")
            val results = JSONArray(resultsStr)

            for (i in 0 until results.length()) {
                val gif = results.getJSONObject(i)

                // Skip files larger than 10 MB
                val sizeStr = gif.optString("os")
                if (isSizeTooLarge(sizeStr)) continue

                val url = gif.optString("ou")
                val thumbnailUrl = gif.optString("tu").takeIf { it.isNotEmpty() }
                val width = gif.optInt("ow", 200)
                val height = gif.optInt("oh", 200)
                val title = gif.optString("pt").takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/')

                if (url.isNotEmpty() && width > 0 && height > 0) {
                    items.add(GifResult(url, title, thumbnailUrl, url, width, height, "Google (Legacy)"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    private fun isSizeTooLarge(sizeStr: String): Boolean {
        if (sizeStr.isEmpty()) return false
        try {
            val upperStr = sizeStr.uppercase(java.util.Locale.US)
            val value = upperStr.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: return false
            return upperStr.endsWith("MB") && value > MAX_FILE_SIZE_MB
        } catch (e: Exception) {
            return false
        }
    }
}
