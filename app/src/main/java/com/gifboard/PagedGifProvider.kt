package com.gifboard

/**
 * Base class for providers whose backend pages results via an offset/page
 * cursor. [GifProvider.search] takes only a [limit], not a page number, so
 * this incrementally fetches and caches pages for the current query: calling
 * search() again with a larger limit for the SAME query returns previously
 * fetched results plus newly fetched ones (i.e. "load more"), while a new
 * query resets the cursor.
 *
 * Not thread-safe; each provider instance is driven from a single coroutine
 * at a time in this app (see GifBoardService).
 */
abstract class PagedGifProvider : GifProvider {

    private var cachedQuery: String? = null
    private val cachedResults = mutableListOf<GifResult>()
    private var nextPage = 0
    private var exhausted = false

    /** Fetches a single 0-indexed page of results. Return an empty list when there are no more. */
    protected abstract suspend fun fetchPage(query: String, page: Int): List<GifResult>

    /**
     * Called when a new query starts (including the very first one), before any
     * [fetchPage] call for it. Override to reset backend-specific pagination
     * state (e.g. an opaque "after" cursor) that doesn't fit a plain page index.
     */
    protected open fun onNewQuery(query: String) {}

    override suspend fun search(query: String, limit: Int): List<GifResult> {
        require(query.isNotBlank()) { "Query cannot be empty" }

        if (query != cachedQuery) {
            cachedQuery = query
            cachedResults.clear()
            nextPage = 0
            exhausted = false
            onNewQuery(query)
        }

        while (cachedResults.size < limit && !exhausted) {
            val page = fetchPage(query, nextPage)
            nextPage++
            if (page.isEmpty()) {
                exhausted = true
            } else {
                cachedResults.addAll(page)
            }
        }

        return cachedResults.take(limit)
    }
}
