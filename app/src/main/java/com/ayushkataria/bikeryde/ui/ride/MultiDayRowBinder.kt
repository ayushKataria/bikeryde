package com.ayushkataria.bikeryde.ui.ride

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideDayType
import java.text.SimpleDateFormat
import java.util.Locale

/** Builds one [R.layout.item_multi_day_row] for a [RideDay] — shared by the live multi-day
 * tracking screen ([MultiDayRideFragment]) and the read-only ride-history detail screen
 * ([RideDetailFragment]), same as [StopsLegendBinder] is shared for stop rows. */
object MultiDayRowBinder {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun buildRow(context: Context, parent: ViewGroup, day: RideDay): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_multi_day_row, parent, false)
        row.findViewById<TextView>(R.id.rowDayTitle).text = context.getString(
            R.string.day_label_format,
            day.dayIndex + 1
        ) + " · " + dateFormat.format(day.startTime)

        val subtitle = row.findViewById<TextView>(R.id.rowDaySubtitle)
        val stats = row.findViewById<TextView>(R.id.rowDayStats)
        when {
            day.dayType == RideDayType.NOT_TRAVEL -> {
                subtitle.text = context.getString(R.string.day_row_rest_title)
                stats.visibility = View.GONE
            }
            day.endTime == null -> {
                subtitle.text = day.startPlaceName ?: context.getString(R.string.unknown_location)
                stats.visibility = View.GONE
            }
            else -> {
                subtitle.text = context.getString(
                    R.string.day_row_travel_format,
                    day.startPlaceName ?: context.getString(R.string.unknown_location),
                    day.endPlaceName ?: context.getString(R.string.unknown_location)
                )
                stats.visibility = View.VISIBLE
                stats.text = context.getString(
                    R.string.day_row_travel_stats_format,
                    RideStatsFormat.distance(day.distanceKm * 1000.0),
                    RideStatsFormat.duration(day.durationS)
                )
            }
        }
        return row
    }
}
