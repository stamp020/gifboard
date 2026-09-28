package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GIF provider backed by the Giphy search API.
 * Requires a user-supplied API key (see https://developers.giphy.com/).
 */
class GiphyGifProvider(val apiKey: String) : GifProvider {

    companion object {
        private const val BASE_URL = "https://api.giphy.com/v1/gifs/search"
        private const val LIMIT = 30
    }

    private val client = NetworkClients.shared

    override suspend fun search(
        query: String,
        page: Int,
        safeSearch: String,
        timeoutMs: Long
    ): List<GifItem> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Query cannot be empty" }
        if (apiKey.isBlank()) {
            throw IllegalStateException("Giphy API key is not set. Add one in Settings.")
        }

        val params = mapOf(
            "api_key" to apiKey,
            "q" to query,
            "limit" to LIMIT.toString(),
            "offset" to (page * LIMIT).toString(),
            "rating" to mapRating(safeSearch)
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val httpRequest = Request.Builder()
            .url("$BASE_URL?$queryString")
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Giphy request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            parseResults(body)
        }
    }

    private fun mapRating(safeSearch: String): String = when (safeSearch) {
        "active" -> "g"
        "medium" -> "pg-13"
        "off" -> "r"
        else -> "g"
    }

    private fun parseResults(jsonResponse: String): List<GifItem> {
        val items = mutableListOf<GifItem>()
        try {
            val json = JSONObject(jsonResponse)
            val data = json.optJSONArray("data") ?: return items

            for (i in 0 until data.length()) {
                val gif = data.getJSONObject(i)
                val images = gif.optJSONObject("images") ?: continue

                val original = images.optJSONObject("original") ?: continue
                val thumbnail = images.optJSONObject("fixed_height_small")
                    ?: images.optJSONObject("preview_gif")

                val url = original.optString("url")
                val width = original.optString("width").toIntOrNull() ?: 200
                val height = original.optString("height").toIntOrNull() ?: 200
                val thumbnailUrl = thumbnail?.optString("url")?.takeIf { it.isNotEmpty() }

                if (url.isNotEmpty() && width > 0 && height > 0) {
                    items.add(GifItem(url, thumbnailUrl, width, height))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }
}
