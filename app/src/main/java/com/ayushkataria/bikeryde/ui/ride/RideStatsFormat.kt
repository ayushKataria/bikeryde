package com.ayushkataria.bikeryde.ui.ride

import java.util.Locale

/** Shared duration/distance formatting for anywhere a ride's stats are displayed. */
object RideStatsFormat {

    fun duration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun distance(distanceM: Double): String =
        String.format(Locale.US, "%.2f km", distanceM / 1000.0)
}
