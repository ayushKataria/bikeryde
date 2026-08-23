package com.ayushkataria.bikeryde.ride

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns all reads/writes of ride data. Every method does its SQLite work on [Dispatchers.IO];
 * callers (the tracking service, the activity) stay on the main thread.
 */
class RideRepository(context: Context) {

    private val dbHelper = RideDbHelper(context.applicationContext)

    suspend fun startRide(startTime: Long, lat: Double?, lng: Double?, placeName: String?): Long =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val rideId = db.insert(
                "ride",
                null,
                ContentValues().apply {
                    put("start_time", startTime)
                    put("status", RideStatus.TRACKING.name)
                    put("total_distance_m", 0.0)
                    put("total_duration_s", 0L)
                }
            )
            insertEvent(db, rideId, RideEventAction.START, startTime, lat, lng, placeName)
            rideId
        }

    suspend fun pauseRide(rideId: Long, timestamp: Long, lat: Double?, lng: Double?, placeName: String?) =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            setStatus(db, rideId, RideStatus.PAUSED)
            insertEvent(db, rideId, RideEventAction.PAUSE, timestamp, lat, lng, placeName)
        }

    suspend fun resumeRide(rideId: Long, timestamp: Long, lat: Double?, lng: Double?, placeName: String?) =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            setStatus(db, rideId, RideStatus.TRACKING)
            insertEvent(db, rideId, RideEventAction.RESUME, timestamp, lat, lng, placeName)
        }

    suspend fun endRide(
        rideId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?,
        totalDistanceM: Double,
        totalDurationS: Long
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.update(
            "ride",
            ContentValues().apply {
                put("end_time", timestamp)
                put("status", RideStatus.COMPLETED.name)
                put("total_distance_m", totalDistanceM)
                put("total_duration_s", totalDurationS)
            },
            "id = ?",
            arrayOf(rideId.toString())
        )
        insertEvent(db, rideId, RideEventAction.END, timestamp, lat, lng, placeName)
    }

    suspend fun addGpsPoint(
        rideId: Long,
        timestamp: Long,
        lat: Double,
        lng: Double,
        elevation: Double?,
        speed: Float?
    ) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insert(
            "gps_point",
            null,
            ContentValues().apply {
                put("ride_id", rideId)
                put("timestamp", timestamp)
                put("lat", lat)
                put("lng", lng)
                putNullableDouble("elevation", elevation)
                if (speed == null) putNull("speed") else put("speed", speed)
            }
        )
    }

    /** All logged GPS fixes for a ride, in recording order — the route to render on [RouteMapView]. */
    suspend fun getRoutePoints(rideId: Long): List<RidePoint> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "gps_point",
            arrayOf("lat", "lng", "speed"),
            "ride_id = ?",
            arrayOf(rideId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            val points = mutableListOf<RidePoint>()
            while (cursor.moveToNext()) {
                points += RidePoint(
                    lat = cursor.getDouble(0),
                    lng = cursor.getDouble(1),
                    speedMps = if (cursor.isNull(2)) null else cursor.getFloat(2)
                )
            }
            points
        }
    }

    /** The start/pause/resume/end control actions for a ride, in chronological order — the route's stop markers. */
    suspend fun getEvents(rideId: Long): List<RideEvent> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride_event",
            arrayOf("action", "timestamp", "lat", "lng", "place_name"),
            "ride_id = ?",
            arrayOf(rideId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            val events = mutableListOf<RideEvent>()
            while (cursor.moveToNext()) {
                events += RideEvent(
                    action = RideEventAction.valueOf(cursor.getString(0)),
                    timestamp = cursor.getLong(1),
                    lat = if (cursor.isNull(2)) null else cursor.getDouble(2),
                    lng = if (cursor.isNull(3)) null else cursor.getDouble(3),
                    placeName = cursor.getString(4)
                )
            }
            events
        }
    }

    /** The fastest single GPS fix recorded for a ride, in m/s — null if no fix reported a speed. */
    suspend fun getMaxSpeedMps(rideId: Long): Float? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "gps_point",
            arrayOf("MAX(speed)"),
            "ride_id = ?",
            arrayOf(rideId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getFloat(0) else null
        }
    }

    /** The most recent ride still in TRACKING or PAUSED state, if any — used to recover UI state. */
    suspend fun getActiveRide(): Ride? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride",
            null,
            "status IN (?, ?)",
            arrayOf(RideStatus.TRACKING.name, RideStatus.PAUSED.name),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRide() else null
        }
    }

    suspend fun getRide(rideId: Long): Ride? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride", null, "id = ?", arrayOf(rideId.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRide() else null
        }
    }

    private fun setStatus(db: android.database.sqlite.SQLiteDatabase, rideId: Long, status: RideStatus) {
        db.update(
            "ride",
            ContentValues().apply { put("status", status.name) },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    private fun insertEvent(
        db: android.database.sqlite.SQLiteDatabase,
        rideId: Long,
        action: RideEventAction,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) {
        db.insert(
            "ride_event",
            null,
            ContentValues().apply {
                put("ride_id", rideId)
                put("action", action.name)
                put("timestamp", timestamp)
                putNullableDouble("lat", lat)
                putNullableDouble("lng", lng)
                put("place_name", placeName)
            }
        )
    }

    private fun Cursor.toRide(): Ride = Ride(
        id = getLong(getColumnIndexOrThrow("id")),
        startTime = getLong(getColumnIndexOrThrow("start_time")),
        endTime = if (isNull(getColumnIndexOrThrow("end_time"))) null else getLong(getColumnIndexOrThrow("end_time")),
        status = RideStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        totalDistanceM = getDouble(getColumnIndexOrThrow("total_distance_m")),
        totalDurationS = getLong(getColumnIndexOrThrow("total_duration_s"))
    )
}
