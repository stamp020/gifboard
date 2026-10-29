package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NsfwMonsterProvider : PagedGifProvider() {

    override fun getName(): String = "NsfwMonster"
    override fun supportsAdultContent(): Boolean = true

    override suspend fun getTrending(limit: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            emptyList()
        }
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                var url = URL("https://nsfwmonster.io/backend.php?p=${page + 1}&device=pc&r=$encoded&sort=&period=&content=all&sourced=0&amp%3Bsort=&amp%3Bperiod=&post=&source=")

                var connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "GifBoard/1.0")
                connection.connectTimeout = 12000
                connection.readTimeout = 12000

                var response = connection.inputStream.bufferedReader().use { it.readText() }

                var results = parseResponse(response)

                // if we got no results from the subreddit, try search
                if (results.isEmpty())
                {
                    url = URL("https://nsfwmonster.io/backend.php?&p=1&device=pc&r=${page + 1}sort=&period=&content=all&sourced=0&search=$encoded&post=&source=")

                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "GifBoard/1.0")
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000

                    response = connection.inputStream.bufferedReader().use { it.readText() }

                    results = parseResponse(response)
                }

                results
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun parseResponse(htmlContent: String): List<GifResult> {
        val results = mutableListOf<GifResult>()
        try {
            val doc: Document = Jsoup.parse(htmlContent)
            val videoElements = doc.select("video")

            for (video in videoElements) {
                val dataPoster = video.attr("data-poster")
                val id = video.attr("data-post-id")
                val title =  video.attr("data-title")
                val width =  video.attr("data-w")
                val height =  video.attr("data-h")
                val sourceElement = video.selectFirst("source")

                if (sourceElement != null) {
                    val dataSrc = sourceElement.attr("data-src")

                    results.add(
                        GifResult(
                            id = id,
                            title = title,
                            previewUrl = dataPoster,
                            fullUrl = dataSrc,
                            width = width.toIntOrNull() ?: 0 ,
                            height = height.toIntOrNull() ?: 0 ,
                            sourceName = "NsfwMonster"
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