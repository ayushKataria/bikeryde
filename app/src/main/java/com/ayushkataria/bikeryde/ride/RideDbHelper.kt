package com.ayushkataria.bikeryde.ride

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Raw SQLite storage for ride tracking data (rides, control-action events, GPS points).
 * Kept as plain SQLite rather than Room so the persistence layer has no extra build-toolchain
 * dependency (annotation processing) beyond what's already wired into this project.
 */
class RideDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ride (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
            CREATE TABLE ride_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL,
                action TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                lat REAL,
                lng REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE gps_point (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL,
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
        db.execSQL("DROP TABLE IF EXISTS ride_event")
        db.execSQL("DROP TABLE IF EXISTS ride")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "bikeryde.db"
        private const val DB_VERSION = 1
    }
}

internal fun ContentValues.putNullableDouble(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}
