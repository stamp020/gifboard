package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GIF provider backed by the Giphy search API.
 * Requires a user-supplied API key (see https://developers.giphy.com/).
 */
class GiphyGifProvider(
    private val apiKey: String,
    private val safeSearch: String = "active"
) : PagedGifProvider() {

    companion object {
        private const val SEARCH_URL = "https://api.giphy.com/v1/gifs/search"
        private const val TRENDING_URL = "https://api.giphy.com/v1/gifs/trending"
        private const val PAGE_SIZE = 30
    }

    private val client = NetworkClients.shared

    override fun getName(): String = "Giphy"

    override fun supportsAdultContent(): Boolean = false

    override suspend fun getTrending(limit: Int): List<GifResult> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Giphy API key is not set. Add one in Settings." }

        val params = mapOf(
            "api_key" to apiKey,
            "limit" to limit.toString(),
            "rating" to mapRating(safeSearch)
        )
        val httpRequest = Request.Builder().url("$TRENDING_URL?${buildQueryString(params)}").get().build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Giphy trending request failed: HTTP ${response.code}")
            }
            parseResults(response.body?.string() ?: "")
        }
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Giphy API key is not set. Add one in Settings.")
        }

        val limit = PAGE_SIZE
        val params = mapOf(
            "api_key" to apiKey,
            "q" to query,
            "limit" to limit.toString(),
            "offset" to (page * limit).toString(),
            "rating" to mapRating(safeSearch)
        )

        val httpRequest = Request.Builder().url("$SEARCH_URL?${buildQueryString(params)}").get().build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Giphy request failed: HTTP ${response.code}")
            }
            parseResults(response.body?.string() ?: "")
        }
    }

    private fun buildQueryString(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

    private fun mapRating(safeSearch: String): String = when (safeSearch) {
        "active" -> "g"
        "medium" -> "pg-13"
        "off" -> "r"
        else -> "g"
    }

    private fun parseResults(jsonResponse: String): List<GifResult> {
        val items = mutableListOf<GifResult>()
        try {
            val json = JSONObject(jsonResponse)
            val data: JSONArray = json.optJSONArray("data") ?: return items

            for (i in 0 until data.length()) {
                val gif = data.getJSONObject(i)
                val images = gif.optJSONObject("images") ?: continue

                val original = images.optJSONObject("original") ?: continue
                val thumbnail = images.optJSONObject("fixed_height_small")
                    ?: images.optJSONObject("preview_gif")

                val id = gif.optString("id").takeIf { it.isNotEmpty() } ?: original.optString("url")
                val title = gif.optString("title").takeIf { it.isNotEmpty() } ?: id
                val url = original.optString("url")
                val width = original.optString("width").toIntOrNull() ?: 200
                val height = original.optString("height").toIntOrNull() ?: 200
                val thumbnailUrl = thumbnail?.optString("url")?.takeIf { it.isNotEmpty() }

                if (url.isNotEmpty() && width > 0 && height > 0) {
                    items.add(GifResult(id, title, thumbnailUrl, url, width, height, "Giphy"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }
}
