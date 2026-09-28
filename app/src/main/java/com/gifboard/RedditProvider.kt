package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GIF provider backed by Reddit's public (unauthenticated) JSON endpoints.
 * No API key required. Scoped to a single subreddit when [subreddit] is
 * non-blank, otherwise searches/browses Reddit site-wide.
 *
 * Only posts linking directly to a `.gif` file (e.g. i.redd.it, imgur) are
 * returned — link posts to hosting pages (redgifs.com, gfycat, ...) aren't
 * resolvable without querying that host's own API, so they're skipped
 * rather than shown as broken results.
 */
class RedditProvider(
    private val subreddit: String = "gifs",
    private val includeAdult: Boolean = false
) : PagedGifProvider() {

    companion object {
        // Reddit requires a descriptive User-Agent; generic/blank ones get rate-limited harder.
        private const val USER_AGENT = "android:com.gifboard:v1.0 (GIF search keyboard)"
        private const val PAGE_SIZE = 25
    }

    private val client = NetworkClients.shared
    private var afterCursor: String? = null
    private var reachedEnd = false

    override fun getName(): String = if (subreddit.isBlank()) "Reddit" else "Reddit (r/$subreddit)"

    override fun supportsAdultContent(): Boolean = true

    override fun onNewQuery(query: String) {
        afterCursor = null
        reachedEnd = false
    }

    override suspend fun getTrending(limit: Int): List<GifResult> = withContext(Dispatchers.IO) {
        val url = if (subreddit.isNotBlank()) {
            "https://www.reddit.com/r/$subreddit/hot.json?limit=$limit&raw_json=1"
        } else {
            "https://www.reddit.com/hot.json?limit=$limit&raw_json=1"
        }
        fetchListing(url, updateCursor = false).take(limit)
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> = withContext(Dispatchers.IO) {
        if (reachedEnd) return@withContext emptyList()

        val params = mutableMapOf(
            "q" to query,
            "limit" to PAGE_SIZE.toString(),
            "sort" to "relevance",
            "include_over_18" to if (includeAdult) "1" else "0",
            "raw_json" to "1"
        )
        afterCursor?.let { params["after"] = it }

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val url = if (subreddit.isNotBlank()) {
            "https://www.reddit.com/r/$subreddit/search.json?$queryString&restrict_sr=1"
        } else {
            "https://www.reddit.com/search.json?$queryString"
        }

        fetchListing(url, updateCursor = true)
    }

    private fun fetchListing(url: String, updateCursor: Boolean): List<GifResult> {
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Reddit request failed: HTTP ${response.code}")
            }
            return parseListing(response.body?.string() ?: "", updateCursor)
        }
    }

    private fun parseListing(jsonResponse: String, updateCursor: Boolean): List<GifResult> {
        val items = mutableListOf<GifResult>()
        try {
            val json = JSONObject(jsonResponse)
            val data = json.optJSONObject("data") ?: return items
            val children = data.optJSONArray("children") ?: return items

            for (i in 0 until children.length()) {
                val postData = children.optJSONObject(i)?.optJSONObject("data") ?: continue
                if (!includeAdult && postData.optBoolean("over_18", false)) continue
                extractGif(postData)?.let { items.add(it) }
            }

            if (updateCursor) {
                val nextAfter = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
                afterCursor = nextAfter
                if (nextAfter == null) {
                    reachedEnd = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /** Extracts a direct GIF asset from a Reddit post, or null if it isn't one. */
    private fun extractGif(postData: JSONObject): GifResult? {
        val directUrl = postData.optString("url_overridden_by_dest").takeIf { it.isNotEmpty() }
            ?: postData.optString("url")

        if (directUrl.isEmpty() || !directUrl.endsWith(".gif", ignoreCase = true)) return null

        val id = postData.optString("id")
        val title = postData.optString("title").takeIf { it.isNotEmpty() } ?: id

        val source = postData.optJSONObject("preview")
            ?.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optJSONObject("source")

        val width = source?.optInt("width", 0) ?: 0
        val height = source?.optInt("height", 0) ?: 0
        val thumbnailUrl = postData.optString("thumbnail").takeIf { it.startsWith("http") }

        return GifResult(
            id = id,
            title = title,
            previewUrl = thumbnailUrl,
            fullUrl = directUrl,
            width = if (width > 0) width else 200,
            height = if (height > 0) height else 200,
            sourceName = "Reddit"
        )
    }
}
