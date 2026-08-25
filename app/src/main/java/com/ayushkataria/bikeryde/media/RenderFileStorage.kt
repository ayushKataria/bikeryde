package com.ayushkataria.bikeryde.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where a generated ride image/video lives before the user explicitly saves it — app-private,
 * persistent storage (`filesDir`, not `cacheDir`, so it survives app restarts and isn't cleared
 * under storage pressure) that's never auto-scanned into the device gallery. It's exposed to other
 * apps (the share sheet, the "Save" picker's read) only via a [FileProvider] content Uri, never a
 * raw file path — nothing is visible outside the app until the user taps Save.
 */
object RenderFileStorage {
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun rendersDir(context: Context): File =
        File(context.filesDir, "renders").apply { mkdirs() }

    fun newImageFile(context: Context): File =
        File(rendersDir(context), "image_${System.currentTimeMillis()}.png")

    fun newVideoFile(context: Context): File =
        File(rendersDir(context), "video_${System.currentTimeMillis()}.mp4")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
}

/** A render's content Uri (for display/share/save) alongside its actual file path (for existence checks and cleanup). */
data class RenderOutput(val uri: Uri, val filePath: String)
