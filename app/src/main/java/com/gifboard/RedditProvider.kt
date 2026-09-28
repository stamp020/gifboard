package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * GIF provider backed by Reddit's official OAuth API.
 *
 * Reddit locked down its plain `www.reddit.com/*.json` endpoints against
 * unauthenticated/non-browser clients (they now return a 403 bot-check page),
 * so this uses Reddit's "installed app" OAuth grant instead: a free,
 * app-only token that doesn't require the end user to log in, just a
 * `client_id` registered once at https://www.reddit.com/prefs/apps
 * (choose "installed app", any redirect URI works since it's unused by
 * this grant type).
 *
 * Scoped to a single subreddit when [subreddit] is non-blank, otherwise
 * searches/browses Reddit site-wide. Only posts linking directly to a
 * `.gif` file (e.g. i.redd.it, imgur) are returned — link posts to hosting
 * pages (redgifs.com, gfycat, ...) aren't resolvable without querying that
 * host's own API, so they're skipped rather than shown as broken results.
 */
class RedditProvider(
    private val clientId: String,
    private val subreddit: String = "gifs",
    private val includeAdult: Boolean = false
) : PagedGifProvider() {

    companion object {
        private const val USER_AGENT = "android:com.gifboard:v1.0 (by /u/gifboard_app)"
        private const val PAGE_SIZE = 25
        private const val TOKEN_URL = "https://www.reddit.com/api/v1/access_token"
        private const val API_BASE = "https://oauth.reddit.com"
        private const val INSTALLED_CLIENT_GRANT = "https://oauth.reddit.com/grants/installed_client"
    }

    private val client = NetworkClients.shared
    private var afterCursor: String? = null
    private var reachedEnd = false

    private var accessToken: String? = null
    private var tokenExpiresAtMs: Long = 0L
    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    override fun getName(): String = if (subreddit.isBlank()) "Reddit" else "Reddit (r/$subreddit)"

    override fun supportsAdultContent(): Boolean = true

    override fun onNewQuery(query: String) {
        afterCursor = null
        reachedEnd = false
    }

    override suspend fun getTrending(limit: Int): List<GifResult> = withContext(Dispatchers.IO) {
        val url = if (subreddit.isNotBlank()) {
            "$API_BASE/r/$subreddit/hot?limit=$limit&raw_json=1"
        } else {
            "$API_BASE/hot?limit=$limit&raw_json=1"
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
            "$API_BASE/r/$subreddit/search?$queryString&restrict_sr=1"
        } else {
            "$API_BASE/search?$queryString"
        }

        fetchListing(url, updateCursor = true)
    }

    private fun fetchListing(url: String, updateCursor: Boolean): List<GifResult> {
        val token = ensureAccessToken()
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (response.code == 401) {
                // Token may have expired/been revoked early - drop it so the next call refreshes.
                accessToken = null
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Reddit request failed: HTTP ${response.code}")
            }
            return parseListing(response.body?.string() ?: "", updateCursor)
        }
    }

    /** Fetches (and caches) a Reddit "installed app" OAuth token. No user login involved. */
    private fun ensureAccessToken(): String {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < tokenExpiresAtMs) {
            return cached
        }

        if (clientId.isBlank()) {
            throw IllegalStateException("Reddit client ID is not set. Add one in Settings.")
        }

        val body = "grant_type=${URLEncoder.encode(INSTALLED_CLIENT_GRANT, "UTF-8")}" +
            "&device_id=${URLEncoder.encode(deviceId, "UTF-8")}"
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", Credentials.basic(clientId, ""))
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Reddit auth failed: HTTP ${response.code}. Check your client ID in Settings.")
            }
            val json = JSONObject(response.body?.string() ?: "")
            val token = json.getString("access_token")
            val expiresInSec = json.optInt("expires_in", 3600)
            accessToken = token
            // Refresh a minute early so an in-flight request never races an expiring token.
            tokenExpiresAtMs = System.currentTimeMillis() + (expiresInSec - 60).coerceAtLeast(0) * 1000L
            return token
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
