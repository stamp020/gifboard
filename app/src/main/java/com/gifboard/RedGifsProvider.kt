package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RedGifsProvider : GifProvider {

    override fun getName(): String = "RedGIFs"

    override fun supportsAdultContent(): Boolean = true

    override suspend fun search(query: String, limit: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val token = getTemporaryToken()
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("https://api.redgifs.com/v2/gifs/search?search_text=$encodedQuery&count=$limit")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "GifBoard/1.0")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseSearchResponse(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    override suspend fun getTrending(limit: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val token = getTemporaryToken()
                val url = URL("https://api.redgifs.com/v2/gifs/trending?count=$limit")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("User-Agent", "GifBoard/1.0")

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseSearchResponse(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private suspend fun getTemporaryToken(): String {
        return withContext(Dispatchers.IO) {
            val url = URL("https://api.redgifs.com/v2/auth/temporary")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "GifBoard/1.0")

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            json.getString("token")
        }
    }

    private fun parseSearchResponse(jsonString: String): List<GifResult> {
        val results = mutableListOf<GifResult>()
        try {
            val json = JSONObject(jsonString)
            val gifs = json.optJSONArray("gifs") ?: return emptyList()

            for (i in 0 until gifs.length()) {
                val gif = gifs.getJSONObject(i)
                val id = gif.optString("id", "")
                val urls = gif.optJSONObject("urls") ?: continue

                val fullUrl = urls.optString("hd", urls.optString("sd", ""))
                val previewUrl = urls.optString("thumbnail", urls.optString("poster", null))

                if (fullUrl.isNotEmpty()) {
                    results.add(
                        GifResult(
                            id = id,
                            title = gif.optString("title", ""),
                            previewUrl = previewUrl,
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