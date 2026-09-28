package com.gifboard

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GIF provider backed by Reddit's public RSS/Atom feeds. No API key or
 * registration required.
 *
 * Reddit locked down its plain www.reddit.com JSON endpoints against
 * unauthenticated/non-browser clients (they now return a 403 bot-check
 * page), and as of their 2023+ API changes, creating a new OAuth app for
 * the legacy Data API now requires a manually-reviewed request with a
 * "valid moderation use case" - not something a generic GIF search feature
 * qualifies for. The RSS feeds, however, are still openly served (subject
 * to normal rate limiting) and carry the same listing data.
 *
 * Each RSS entry's HTML content embeds the post's direct target as a
 * `[link]` anchor (Reddit's classic feed format), which is parsed out here.
 * Only posts whose direct link ends in `.gif` are returned - link posts to
 * hosting pages (redgifs.com, gfycat, ...) aren't resolvable without
 * querying that host's own API, so they're skipped rather than shown as
 * broken results.
 *
 * Anonymous RSS requests don't expose an NSFW toggle the way the JSON API
 * did (no `include_over_18` equivalent), and Reddit excludes NSFW-flagged
 * posts from anonymous feeds by default - so there's no safe-search knob
 * to wire up here. [supportsAdultContent] stays true since that default
 * isn't a documented contract, just observed behavior.
 */
class RedditProvider(
    private val subreddit: String = "gifs"
) : PagedGifProvider() {

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private const val PAGE_SIZE = 25
        private val LINK_PATTERN = Regex("""href="([^"]+)">\[link]""")
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
            "https://www.reddit.com/r/$subreddit/.rss?limit=$limit"
        } else {
            "https://www.reddit.com/r/popular/.rss?limit=$limit"
        }
        fetchFeed(url, updateCursor = false).take(limit)
    }

    override suspend fun fetchPage(query: String, page: Int): List<GifResult> = withContext(Dispatchers.IO) {
        if (reachedEnd) return@withContext emptyList()

        val params = mutableMapOf(
            "q" to query,
            "limit" to PAGE_SIZE.toString(),
            "sort" to "relevance"
        )
        afterCursor?.let { params["after"] = it }

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }

        val url = if (subreddit.isNotBlank()) {
            "https://www.reddit.com/r/$subreddit/search.rss?$queryString&restrict_sr=1"
        } else {
            "https://www.reddit.com/search.rss?$queryString"
        }

        fetchFeed(url, updateCursor = true)
    }

    private fun fetchFeed(url: String, updateCursor: Boolean): List<GifResult> {
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Reddit request failed: HTTP ${response.code}")
            }
            val (items, lastEntryId) = parseFeed(response.body?.string() ?: "")
            if (updateCursor) {
                if (lastEntryId == null) {
                    reachedEnd = true
                } else {
                    afterCursor = lastEntryId
                }
            }
            return items
        }
    }

    /** Parses a Reddit Atom feed. Returns the results plus the last entry's fullname (for `after` pagination). */
    private fun parseFeed(xml: String): Pair<List<GifResult>, String?> {
        val items = mutableListOf<GifResult>()
        var lastEntryId: String? = null

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var inEntry = false
            var currentId = ""
            var currentTitle = ""
            var currentThumbnail: String? = null
            var currentContent = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            currentId = ""
                            currentTitle = ""
                            currentThumbnail = null
                            currentContent = ""
                        }
                        "id" -> if (inEntry) currentId = parser.nextText()
                        "title" -> if (inEntry) currentTitle = parser.nextText()
                        "content" -> if (inEntry) currentContent = parser.nextText()
                        "media:thumbnail" -> if (inEntry) currentThumbnail = parser.getAttributeValue(null, "url")
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "entry" && inEntry) {
                        inEntry = false
                        if (currentId.isNotEmpty()) lastEntryId = currentId

                        val directUrl = LINK_PATTERN.find(currentContent)?.groupValues?.get(1)
                        if (directUrl != null && directUrl.endsWith(".gif", ignoreCase = true)) {
                            items.add(
                                GifResult(
                                    id = currentId,
                                    title = currentTitle.ifEmpty { currentId },
                                    previewUrl = currentThumbnail,
                                    fullUrl = directUrl,
                                    width = 200,
                                    height = 200,
                                    sourceName = "Reddit"
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return items to lastEntryId
    }
}
