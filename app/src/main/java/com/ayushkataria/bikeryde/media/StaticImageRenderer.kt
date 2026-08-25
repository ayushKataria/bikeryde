package com.ayushkataria.bikeryde.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composites a ride's route + stats into a single image — the one-frame case of [RouteFrameDrawer],
 * drawn at `progress = 1f` (the full, final route). Writes only to app-private storage
 * ([RenderFileStorage]) — this is a preview; nothing is saved anywhere the user or other apps can
 * see until they explicitly tap Save on [com.ayushkataria.bikeryde.ui.render.RenderPreviewFragment].
 */
class StaticImageRenderer(private val context: Context) {

    suspend fun render(data: RideRenderData): RenderOutput {
        data.coverImagePath?.let { BackgroundImageCache.preload(listOf(it), WIDTH, HEIGHT) }
        val bitmap = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
                RouteFrameDrawer.prepare(WIDTH, HEIGHT, data).draw(Canvas(it), progress = 1f)
            }
        }
        BackgroundImageCache.clear()
        return withContext(Dispatchers.IO) {
            val file = RenderFileStorage.newImageFile(context)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            RenderOutput(RenderFileStorage.uriFor(context, file), file.absolutePath)
        }
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val RESOLUTION_LABEL = "1080x1920"
    }
}
