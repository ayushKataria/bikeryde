package com.ayushkataria.bikeryde.fuel

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ayushkataria.bikeryde.ride.RideDbHelper
import com.ayushkataria.bikeryde.ride.putNullableDouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns all reads/writes of [FuelLog] rows. Shares [RideDbHelper]'s single SQLite database file
 * rather than opening a second one, same as [com.ayushkataria.bikeryde.media.RenderRepository]. */
class FuelRepository(context: Context) {

    private val dbHelper = RideDbHelper.getInstance(context)

    /**
     * Records a fill-up, deriving [FuelLog.pricePerLiter] and [FuelLog.mileageSinceLastKm] from
     * the entry with the highest odometer reading below [odoKm] — the fill-up this one's distance
     * was actually ridden since — rather than assuming entries are added in chronological order.
     */
    suspend fun addFuelLog(
        timestamp: Long,
        odoKm: Double,
        litersFilled: Double,
        cost: Double,
        notes: String?
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val previous = previousEntry(db, odoKm)
        val pricePerLiter = if (litersFilled > 0) cost / litersFilled else 0.0
        val mileageSinceLastKm = previous?.let { prev ->
            if (litersFilled > 0) (odoKm - prev.odoKm) / litersFilled else null
        }
        db.insert(
            "fuel_log",
            null,
            ContentValues().apply {
                put("timestamp", timestamp)
                put("odo_km", odoKm)
                put("liters_filled", litersFilled)
                put("cost", cost)
                put("price_per_liter", pricePerLiter)
                putNullableDouble("mileage_since_last_km", mileageSinceLastKm)
                put("notes", notes?.trim()?.takeIf { it.isNotEmpty() })
            }
        )
    }

    suspend fun deleteFuelLog(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("fuel_log", "id = ?", arrayOf(id.toString()))
    }

    /** All fill-ups, most recent first — the fuel log history list. */
    suspend fun getFuelLogs(): List<FuelLog> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "fuel_log", null, null, null, null, null, "timestamp DESC"
        ).use { cursor ->
            val logs = mutableListOf<FuelLog>()
            while (cursor.moveToNext()) logs += cursor.toFuelLog()
            logs
        }
    }

    /** The highest-odometer fill-up below [odoKm], if any — the "previous entry" a new fill-up's
     * derived fields are computed against. */
    private fun previousEntry(db: SQLiteDatabase, odoKm: Double): FuelLog? = db.query(
        "fuel_log", null, "odo_km < ?", arrayOf(odoKm.toString()), null, null, "odo_km DESC", "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toFuelLog() else null }

    private fun Cursor.toFuelLog(): FuelLog = FuelLog(
        id = getLong(getColumnIndexOrThrow("id")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        odoKm = getDouble(getColumnIndexOrThrow("odo_km")),
        litersFilled = getDouble(getColumnIndexOrThrow("liters_filled")),
        cost = getDouble(getColumnIndexOrThrow("cost")),
        pricePerLiter = getDouble(getColumnIndexOrThrow("price_per_liter")),
        mileageSinceLastKm = getColumnIndexOrThrow("mileage_since_last_km").let { i ->
            if (isNull(i)) null else getDouble(i)
        },
        notes = getString(getColumnIndexOrThrow("notes"))
    )
}
