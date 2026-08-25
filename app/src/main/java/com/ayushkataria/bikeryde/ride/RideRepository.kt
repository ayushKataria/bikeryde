package com.ayushkataria.bikeryde.ride

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ids returned once a ride (and its first [RideDay]) have been created. */
data class RideSession(val rideId: Long, val rideDayId: Long)

/**
 * Owns all reads/writes of ride data. Every method does its SQLite work on [Dispatchers.IO];
 * callers (the tracking service, the activity) stay on the main thread.
 *
 * Storage mirrors the design doc's Ride -> RideDay -> {Stop, GpsPoint} shape. A single-day ride
 * gets exactly one RideDay (dayIndex 0), created alongside the ride itself.
 */
class RideRepository(context: Context) {

    private val dbHelper = RideDbHelper(context.applicationContext)

    /** Starts a new single-day ride: creates the [Ride], its sole [RideDay], and a START [Stop]. */
    suspend fun startRide(startTime: Long, lat: Double?, lng: Double?, placeName: String?): RideSession =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val rideId = db.insert(
                "ride",
                null,
                ContentValues().apply {
                    put("type", RideType.SINGLE_DAY.name)
                    put("start_time", startTime)
                    put("status", RideStatus.TRACKING.name)
                    put("total_distance_m", 0.0)
                    put("total_duration_s", 0L)
                }
            )
            val rideDayId = db.insert(
                "ride_day",
                null,
                ContentValues().apply {
                    put("ride_id", rideId)
                    put("day_index", 0)
                    put("start_time", startTime)
                    put("start_place_name", placeName)
                    put("distance_km", 0.0)
                    put("duration_s", 0L)
                }
            )
            insertStop(db, rideDayId, RideEventAction.START, startTime, lat, lng, placeName)
            RideSession(rideId, rideDayId)
        }

    suspend fun pauseRide(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        setStatus(db, rideId, RideStatus.PAUSED)
        insertStop(db, rideDayId, RideEventAction.PAUSE, timestamp, lat, lng, placeName)
    }

    suspend fun resumeRide(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        setStatus(db, rideId, RideStatus.TRACKING)
        insertStop(db, rideDayId, RideEventAction.RESUME, timestamp, lat, lng, placeName)
    }

    suspend fun endRide(
        rideId: Long,
        rideDayId: Long,
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
        db.update(
            "ride_day",
            ContentValues().apply {
                put("end_time", timestamp)
                put("end_place_name", placeName)
                put("distance_km", totalDistanceM / 1000.0)
                put("duration_s", totalDurationS)
            },
            "id = ?",
            arrayOf(rideDayId.toString())
        )
        insertStop(db, rideDayId, RideEventAction.END, timestamp, lat, lng, placeName)
    }

    suspend fun addGpsPoint(
        rideDayId: Long,
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
                put("ride_day_id", rideDayId)
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
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT gps_point.lat, gps_point.lng, gps_point.speed
            FROM gps_point
            JOIN ride_day ON gps_point.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            ORDER BY gps_point.id ASC
            """.trimIndent(),
            arrayOf(rideId.toString())
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
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT stop.action, stop.timestamp, stop.lat, stop.lng, stop.place_name
            FROM stop
            JOIN ride_day ON stop.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            ORDER BY stop.id ASC
            """.trimIndent(),
            arrayOf(rideId.toString())
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
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT MAX(gps_point.speed)
            FROM gps_point
            JOIN ride_day ON gps_point.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            """.trimIndent(),
            arrayOf(rideId.toString())
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

    private fun setStatus(db: SQLiteDatabase, rideId: Long, status: RideStatus) {
        db.update(
            "ride",
            ContentValues().apply { put("status", status.name) },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    private fun insertStop(
        db: SQLiteDatabase,
        rideDayId: Long,
        action: RideEventAction,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) {
        db.insert(
            "stop",
            null,
            ContentValues().apply {
                put("ride_day_id", rideDayId)
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
        type = RideType.valueOf(getString(getColumnIndexOrThrow("type"))),
        startTime = getLong(getColumnIndexOrThrow("start_time")),
        endTime = if (isNull(getColumnIndexOrThrow("end_time"))) null else getLong(getColumnIndexOrThrow("end_time")),
        status = RideStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        totalDistanceM = getDouble(getColumnIndexOrThrow("total_distance_m")),
        totalDurationS = getLong(getColumnIndexOrThrow("total_duration_s"))
    )
}
