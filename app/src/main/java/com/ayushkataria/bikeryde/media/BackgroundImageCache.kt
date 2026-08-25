package com.ayushkataria.bikeryde.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes background photos once, up front, and pre-crops/scales each to exactly the render
 * canvas size — a video re-draws the same handful of stop photos up to a couple hundred times, and
 * either re-decoding from disk or re-resampling a larger bitmap to fit the canvas on every single
 * frame (both used to happen here) was expensive enough on the encoder's *software*-rendered
 * Surface canvas to noticeably inflate render time, especially with two photos drawn per frame
 * during a crossfade. Pre-scaling once turns every frame's draw into a plain same-size blit.
 * Call [preload] before a render starts and [clear] when it finishes to free the memory.
 */
object BackgroundImageCache {
    private val cache = mutableMapOf<String, Bitmap>()

    /** Decodes, center-crops, and scales every path in [paths] not already cached to exactly
     * [targetWidth]x[targetHeight] — the render canvas size. Call before drawing any frames. */
    suspend fun preload(paths: Collection<String>, targetWidth: Int, targetHeight: Int) {
        for (path in paths.distinct()) {
            if (cache.containsKey(path)) continue
            val decoded = RenderImageStorage.decodeSampledBitmap(path, targetWidth, targetHeight) ?: continue
            val prescaled = withContext(Dispatchers.Default) {
                val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                drawCenterCrop(
                    Canvas(output),
                    decoded,
                    RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                output
            }
            decoded.recycle()
            cache[path] = prescaled
        }
    }

    /** Cache-only lookup — returns null if [path] wasn't (or couldn't be) [preload]ed. Already
     * exactly the render canvas size, so callers can draw it directly at (0, 0) with no scaling. */
    fun get(path: String): Bitmap? = cache[path]

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    /** Draws [bitmap] to fill [dest] entirely, center-cropping so it isn't stretched. Used only for
     * the one-time [preload] resize now — per-frame drawing draws an already-sized bitmap directly. */
    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dest: RectF, paint: Paint) {
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
