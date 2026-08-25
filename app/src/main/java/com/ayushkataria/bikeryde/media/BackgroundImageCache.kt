package com.ayushkataria.bikeryde.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Decodes background photos once, up front, and reuses them across frames — a video re-draws the
 * same handful of stop photos up to a couple hundred times, and re-decoding from disk each time
 * (or decoding lazily while the encoder's Surface canvas is locked) would be wasteful/risky. Call
 * [preload] before a render starts and [clear] when it finishes to free the memory.
 */
object BackgroundImageCache {
    private val cache = mutableMapOf<String, Bitmap>()

    /** Bound decoded background bitmaps to roughly the render canvas size — several stop photos
     * can be held in memory at once for a video's crossfade, so avoid full-resolution decodes. */
    private const val MAX_DIMENSION = 1440

    /** Decodes and caches every path in [paths] not already cached. Call before drawing any frames. */
    suspend fun preload(paths: Collection<String>) {
        for (path in paths.distinct()) {
            if (cache.containsKey(path)) continue
            RenderImageStorage.decodeSampledBitmap(path, MAX_DIMENSION, MAX_DIMENSION)?.let { cache[path] = it }
        }
    }

    /** Cache-only lookup — returns null if [path] wasn't (or couldn't be) [preload]ed. */
    fun get(path: String): Bitmap? = cache[path]

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    /** Draws [bitmap] to fill [dest] entirely, center-cropping so it isn't stretched. */
    fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dest: RectF, paint: Paint) {
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destAspect = dest.width() / dest.height()
        val src = if (bitmapAspect > destAspect) {
            // Bitmap is relatively wider than dest — crop its sides.
            val cropWidth = (bitmap.height * destAspect).toInt().coerceAtMost(bitmap.width)
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / destAspect).toInt().coerceAtMost(bitmap.height)
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }
        canvas.drawBitmap(bitmap, src, dest, paint)
    }
}
