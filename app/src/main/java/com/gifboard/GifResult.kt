package com.gifboard

/**
 * A single GIF search result, normalized across providers.
 */
data class GifResult(
    val id: String,
    val title: String,
    val previewUrl: String?,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val sourceName: String
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    // Mutable UI state for tracking load failures (used when live previews enabled)
    var isFullLoadFailed: Boolean = false
}
