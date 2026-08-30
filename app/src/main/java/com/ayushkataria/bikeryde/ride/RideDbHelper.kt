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
 *
 * Obtain instances via [getInstance], never the constructor directly — [SQLiteOpenHelper] opens
 * its own connection pool per instance, and every repository ([com.ayushkataria.bikeryde.ride.RideRepository],
 * [com.ayushkataria.bikeryde.media.RenderRepository], [com.ayushkataria.bikeryde.fuel.FuelRepository])
 * is itself freshly constructed on almost every screen/service/worker. Without a shared singleton,
 * each of those created (and never closed) its own helper/connection pool, which Android's
 * finalizer eventually flags as a leaked `SQLiteConnectionPool`.
 */
class RideDbHelper private constructor(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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
                total_duration_s INTEGER NOT NULL DEFAULT 0,
                title TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE ride_day (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL,
                day_index INTEGER NOT NULL,
                day_type TEXT NOT NULL DEFAULT 'TRAVEL',
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
                "action" TEXT NOT NULL,
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
        db.execSQL(
            """
            CREATE TABLE render (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ride_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                resolution TEXT,
                fps INTEGER,
                file_path TEXT,
                work_id TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(FUEL_LOG_TABLE_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE ride ADD COLUMN title TEXT")
        }
        if (oldVersion < 8) {
            db.execSQL(FUEL_LOG_TABLE_SQL)
        }
    }

    companion object {
        private const val DB_NAME = "bikeryde.db"
        private const val DB_VERSION = 8

        private const val FUEL_LOG_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS fuel_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                odo_km REAL NOT NULL,
                liters_filled REAL NOT NULL,
                cost REAL NOT NULL,
                price_per_liter REAL NOT NULL,
                mileage_since_last_km REAL,
                notes TEXT
            )
        """

        @Volatile
        private var instance: RideDbHelper? = null

        /** The one shared instance for the whole process — safe to call from any thread. */
        fun getInstance(context: Context): RideDbHelper =
            instance ?: synchronized(this) {
                instance ?: RideDbHelper(context.applicationContext).also { instance = it }
            }
    }
}

internal fun ContentValues.putNullableDouble(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}
