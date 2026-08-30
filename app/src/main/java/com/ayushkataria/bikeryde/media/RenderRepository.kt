package com.ayushkataria.bikeryde.media

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.ayushkataria.bikeryde.ride.RideDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a "Create Static Image"/"Create Animation" tap should go, based on what already exists for this ride. */
sealed class RenderNavigationTarget {
    /** A finished render whose file still exists — show it directly. */
    data class ShowResult(val fileUri: android.net.Uri) : RenderNavigationTarget()
    /** A video that's still QUEUED/PROCESSING — reattach to that exact [androidx.work.WorkRequest]
     * instead of opening the customize screen and risking a second, conflicting render. */
    data class FollowInProgress(val workId: String) : RenderNavigationTarget()
    /** Nothing usable exists yet (or the last attempt failed) — open the customize screen. */
    object OpenCustomize : RenderNavigationTarget()
}

/** Owns all reads/writes of [Render] rows — the queued/completed static-image and video exports for a ride. */
class RenderRepository(context: Context) {

    private val dbHelper = RideDbHelper.getInstance(context)

    suspend fun insertQueued(rideId: Long, type: RenderType, workId: String? = null): Long =
        withContext(Dispatchers.IO) {
            dbHelper.writableDatabase.insert(
                "render",
                null,
                ContentValues().apply {
                    put("ride_id", rideId)
                    put("type", type.name)
                    put("status", RenderStatus.QUEUED.name)
                    put("work_id", workId)
                    put("created_at", System.currentTimeMillis())
                }
            )
        }

    suspend fun markProcessing(renderId: Long) = withContext(Dispatchers.IO) {
        setStatus(renderId, RenderStatus.PROCESSING)
    }

    suspend fun markFailed(renderId: Long) = withContext(Dispatchers.IO) {
        setStatus(renderId, RenderStatus.FAILED)
    }

    suspend fun markDone(renderId: Long, filePath: String, resolution: String, fps: Int) =
        withContext(Dispatchers.IO) {
            dbHelper.writableDatabase.update(
                "render",
                ContentValues().apply {
                    put("status", RenderStatus.DONE.name)
                    put("file_path", filePath)
                    put("resolution", resolution)
                    put("fps", fps)
                },
                "id = ?",
                arrayOf(renderId.toString())
            )
        }

    /** The most recent render of a given type for a ride, if any — used to restore the post-ride screen. */
    suspend fun getLatest(rideId: Long, type: RenderType): Render? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "render",
            null,
            "ride_id = ? AND type = ?",
            arrayOf(rideId.toString(), type.name),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRender() else null
        }
    }

    /**
     * Decides what "Create Static Image"/"Create Animation" should do for this ride: show an
     * already-finished render, reattach to one still in progress, or open the customize screen —
     * the single source of truth both [com.ayushkataria.bikeryde.ui.render.RenderLauncher] call
     * sites (live tracking screen, ride history detail) go through.
     */
    suspend fun resolveNavigationTarget(context: Context, rideId: Long, type: RenderType): RenderNavigationTarget =
        withContext(Dispatchers.IO) {
            val render = getLatest(rideId, type)
            val path = render?.filePath
            if (render?.status == RenderStatus.DONE && path != null && File(path).exists()) {
                return@withContext RenderNavigationTarget.ShowResult(RenderFileStorage.uriFor(context, File(path)))
            }
            val workId = render?.workId
            if (type == RenderType.VIDEO &&
                (render?.status == RenderStatus.QUEUED || render?.status == RenderStatus.PROCESSING) &&
                workId != null
            ) {
                return@withContext RenderNavigationTarget.FollowInProgress(workId)
            }
            RenderNavigationTarget.OpenCustomize
        }

    private fun setStatus(renderId: Long, status: RenderStatus) {
        dbHelper.writableDatabase.update(
            "render",
            ContentValues().apply { put("status", status.name) },
            "id = ?",
            arrayOf(renderId.toString())
        )
    }

    private fun Cursor.toRender(): Render = Render(
        id = getLong(getColumnIndexOrThrow("id")),
        rideId = getLong(getColumnIndexOrThrow("ride_id")),
        type = RenderType.valueOf(getString(getColumnIndexOrThrow("type"))),
        status = RenderStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        resolution = getString(getColumnIndexOrThrow("resolution")).orEmpty(),
        fps = getInt(getColumnIndexOrThrow("fps")),
        filePath = getString(getColumnIndexOrThrow("file_path")),
        workId = getString(getColumnIndexOrThrow("work_id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )
}
