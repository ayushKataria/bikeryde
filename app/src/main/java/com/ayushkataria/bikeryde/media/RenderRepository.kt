package com.ayushkataria.bikeryde.media

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.ayushkataria.bikeryde.ride.RideDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns all reads/writes of [Render] rows — the queued/completed static-image and video exports for a ride. */
class RenderRepository(context: Context) {

    private val dbHelper = RideDbHelper(context.applicationContext)

    suspend fun insertQueued(rideId: Long, type: RenderType): Long = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insert(
            "render",
            null,
            ContentValues().apply {
                put("ride_id", rideId)
                put("type", type.name)
                put("status", RenderStatus.QUEUED.name)
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
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )
}
