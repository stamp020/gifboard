package com.gifboard

/**
 * A pluggable GIF search backend.
 *
 * Implementations are expected to be lightweight, per-configuration instances
 * (see GifBoardService.resolveProvider) rather than long-lived singletons, so
 * that a change in settings (API key, safe search level, subreddit, ...) is
 * picked up by simply constructing a new instance.
 */
interface GifProvider {
    /**
     * Searches for GIFs matching [query]. Calling this again with a larger
     * [limit] for the same query should extend the previous result set
     * (see [PagedGifProvider]) rather than starting over, so callers can use
     * growing [limit] values to implement "load more".
     */
    suspend fun search(query: String, limit: Int = 25): List<GifResult>

    /** Fetches currently trending/popular GIFs, if the backend supports the concept. */
    suspend fun getTrending(limit: Int = 25): List<GifResult>

    /** Short, human-readable name shown in the UI (e.g. provider tabs). */
    fun getName(): String

    /** Whether this provider's results can include adult/NSFW content. */
    fun supportsAdultContent(): Boolean
}
