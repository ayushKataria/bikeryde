package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideEvent
import com.ayushkataria.bikeryde.ride.RideEventAction

/**
 * A run of consecutive [RideEvent]s recorded at the same place — e.g. a Start immediately followed
 * by a Pause at the same spot — collapsed into one logical stop. [primaryAction] (the first action
 * in the run) decides the marker color; [actions] is kept for display ("Start, Paused") on the
 * customize screen.
 */
data class MergedStop(
    val primaryAction: RideEventAction,
    val actions: List<RideEventAction>,
    val timestamp: Long,
    val lat: Double?,
    val lng: Double?,
    val placeName: String?
)

/**
 * Merges consecutive stops sharing the same recorded place name so a quick Start-then-Pause (or
 * Pause-then-Resume) at one spot becomes a single editable/renderable stop instead of two
 * overlapping markers and two identically-named rows on the customize screen. Both the edit screen
 * and [RideRenderDataAssembler] must use this same grouping so their stop indices line up.
 */
object StopGrouping {

    fun merge(stops: List<RideEvent>): List<MergedStop> {
        val result = mutableListOf<MergedStop>()
        for (stop in stops) {
            val last = result.lastOrNull()
            if (last != null && placesMatch(last.placeName, stop.placeName)) {
                result[result.lastIndex] = last.copy(actions = last.actions + stop.action)
            } else {
                result += MergedStop(
                    primaryAction = stop.action,
                    actions = listOf(stop.action),
                    timestamp = stop.timestamp,
                    lat = stop.lat,
                    lng = stop.lng,
                    placeName = stop.placeName
                )
            }
        }
        return result
    }

    private fun placesMatch(a: String?, b: String?): Boolean =
        a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)
}
