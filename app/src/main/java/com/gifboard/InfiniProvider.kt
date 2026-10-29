package com.gifboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

class InfiniProvider : PagedGifProvider() {

    override fun getName(): String = "Infini"
    override fun supportsAdultContent(): Boolean = true

    override suspend fun getTrending(limit: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            emptyList()
        }
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query.replace(" ", "-"), "UTF-8")

                // for some reason page starts at 1
                val url = URL("https://infini.wtf/search/$encoded?format=turbo_stream&media_type=videos&nsfw=true&page=${page + 1}")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
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

    private fun parseResponse(htmlContent: String): List<GifResult> {
        val results = mutableListOf<GifResult>()
        try {
            val doc: Document = Jsoup.parse(htmlContent)
            val gridCells: Elements = doc.select(".grid-cell")

            for (gridCell in gridCells) {
                val dataPoster = gridCell.attr("data-poster")
                val id = gridCell.attr("data-uuid")
                val title = gridCell.attr("data-title")
                val lgSize = gridCell.attr("data-lg-size")
                val videoJson = gridCell.attr("data-video")
                val splits = lgSize.split("-")
                val width = splits.getOrNull(0)
                val height = splits.getOrNull(1)
                val videoJsonElement = Json.parseToJsonElement(videoJson)
                val videoJsonObject = videoJsonElement.jsonObject
                val source = videoJsonObject["source"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("src")?.jsonPrimitive?.content

                if (source != null && source != "") {
                    results.add(
                        GifResult(
                            id = id ?: "",
                            title = title ?: "",
                            previewUrl = dataPoster,
                            fullUrl = source,
                            width = width?.toIntOrNull() ?: 0 ,
                            height = height?.toIntOrNull() ?: 0 ,
                            sourceName = "Infini"
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