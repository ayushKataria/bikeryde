package com.ayushkataria.bikeryde.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composites a ride's route + stats into a single shareable image — the one-frame case of
 * [RouteFrameDrawer], drawn at `progress = 1f` (the full, final route). Near-instant since it's a
 * single Canvas draw, no encoding involved.
 */
class StaticImageRenderer(private val context: Context) {

    suspend fun render(data: RideRenderData): Uri {
        data.coverImagePath?.let { BackgroundImageCache.preload(listOf(it)) }
        val bitmap = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
                RouteFrameDrawer.draw(Canvas(it), WIDTH, HEIGHT, data, progress = 1f)
            }
        }
        BackgroundImageCache.clear()
        return withContext(Dispatchers.IO) { saveToGallery(bitmap) }
    }

    private fun saveToGallery(bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val filename = "bikeryde_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BikeRyde")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore entry for ride image")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val RESOLUTION_LABEL = "1080x1920"
    }
}
