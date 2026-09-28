package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RedGifsProvider : PagedGifProvider() {   // ← must extend PagedGifProvider

    override fun getName(): String = "RedGIFs"
    override fun supportsAdultContent(): Boolean = true

    override suspend fun getTrending(limit: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val token = getTemporaryToken()
                val url = URL("https://api.redgifs.com/v2/gifs/search?type=g&order=trending&count=$limit")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "GifBoard/1.0")
                connection.connectTimeout = 12000
                connection.readTimeout = 12000

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseResponse(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val token = getTemporaryToken()
                val encoded = URLEncoder.encode(query, "UTF-8")

                val url = URL(
                    "https://api.redgifs.com/v2/gifs/search" +
                    "?type=g" +
                    "&tags=$encoded" +
                    "&order=trending" +
                    "&count=30" +
                    "&page=$page"
                )

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "GifBoard/1.0")
                connection.connectTimeout = 12000
                connection.readTimeout = 12000

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseResponse(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private suspend fun getTemporaryToken(): String = withContext(Dispatchers.IO) {
        val url = URL("https://api.redgifs.com/v2/auth/temporary")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "GifBoard/1.0")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        json.getString("token")
    }

    private fun parseResponse(jsonString: String): List<GifResult> {
        val results = mutableListOf<GifResult>()
        try {
            val root = JSONObject(jsonString)
            val gifs = root.optJSONArray("gifs") ?: return emptyList()

            for (i in 0 until gifs.length()) {
                val gif = gifs.getJSONObject(i)
                val id = gif.optString("id")
                val urls = gif.optJSONObject("urls") ?: continue

                // Prefer direct media links that can actually be sent
                val fullUrl = urls.optString("hd")
                    .ifEmpty { urls.optString("sd") }
                    .ifEmpty { urls.optString("gif") }

                val preview = urls.optString("thumbnail")
                    .ifEmpty { urls.optString("poster") }
                    .takeIf { it.isNotBlank() }

                if (fullUrl.isNotBlank()) {
                    results.add(
                        GifResult(
                            id = id,
                            title = gif.optString("title", ""),
                            previewUrl = preview,
                            fullUrl = fullUrl,
                            width = gif.optInt("width", 0),
                            height = gif.optInt("height", 0),
                            sourceName = "RedGIFs"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }
}