package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GIF provider backed by the Tenor v2 search API.
 * Requires a user-supplied API key (see https://tenor.com/gifapi/documentation).
 */
class TenorGifProvider(val apiKey: String) : GifProvider {

    companion object {
        private const val BASE_URL = "https://tenor.googleapis.com/v2/search"
        private const val LIMIT = 30
        private const val CLIENT_KEY = "gifboard"
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
            throw IllegalStateException("Tenor API key is not set. Add one in Settings.")
        }

        val params = mapOf(
            "q" to query,
            "key" to apiKey,
            "client_key" to CLIENT_KEY,
            "limit" to LIMIT.toString(),
            "contentfilter" to mapContentFilter(safeSearch),
            // Tenor's "pos" cursor is opaque, but paging by offset works in practice for
            // the search endpoint since results are stable for a given query.
            "pos" to if (page > 0) (page * LIMIT).toString() else ""
        )

        val queryString = params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
            }

        val httpRequest = Request.Builder()
            .url("$BASE_URL?$queryString")
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Tenor request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            parseResults(body)
        }
    }

    private fun mapContentFilter(safeSearch: String): String = when (safeSearch) {
        "active" -> "high"
        "medium" -> "medium"
        "off" -> "off"
        else -> "high"
    }

    private fun parseResults(jsonResponse: String): List<GifItem> {
        val items = mutableListOf<GifItem>()
        try {
            val json = JSONObject(jsonResponse)
            val results = json.optJSONArray("results") ?: return items

            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val mediaFormats = result.optJSONObject("media_formats") ?: continue

                val full = mediaFormats.optJSONObject("gif") ?: continue
                val thumb = mediaFormats.optJSONObject("tinygif")

                val url = full.optString("url")
                val dims = full.optJSONArray("dims")
                val width = dims?.optInt(0, 200) ?: 200
                val height = dims?.optInt(1, 200) ?: 200
                val thumbnailUrl = thumb?.optString("url")?.takeIf { it.isNotEmpty() }

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
