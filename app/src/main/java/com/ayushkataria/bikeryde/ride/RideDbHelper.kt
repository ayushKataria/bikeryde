package com.ayushkataria.bikeryde.ride

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Raw SQLite storage for ride tracking data (rides, ride days, stops, GPS points), matching the
 * Ride -> RideDay -> {Stop, GpsPoint} shape from the design doc's data model. Kept as plain SQLite
 * rather than Room so the persistence layer has no extra build-toolchain dependency (annotation
 * processing) beyond what's already wired into this project.
 */
class RideDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ride (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                status TEXT NOT NULL,
                total_distance_m REAL NOT NULL DEFAULT 0,
                total_duration_s INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE ride_day (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL,
                day_index INTEGER NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                start_place_name TEXT,
                end_place_name TEXT,
                distance_km REAL NOT NULL DEFAULT 0,
                duration_s INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE stop (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_day_id INTEGER NOT NULL,
                action TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                lat REAL,
                lng REAL,
                place_name TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE gps_point (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_day_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                elevation REAL,
                speed REAL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS gps_point")
        db.execSQL("DROP TABLE IF EXISTS stop")
        db.execSQL("DROP TABLE IF EXISTS ride_event")
        db.execSQL("DROP TABLE IF EXISTS ride_day")
        db.execSQL("DROP TABLE IF EXISTS ride")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "bikeryde.db"
        private const val DB_VERSION = 3
    }
}

internal fun ContentValues.putNullableDouble(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}
