package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideEvent
import com.ayushkataria.bikeryde.ride.RideEventAction

/**
 * A Pause immediately followed by its Resume — the same physical stop, just opened and closed —
 * collapsed into one logical stop. [primaryAction] is always the Pause; [actions] is kept for
 * display ("Pause, Resume") on the customize screen.
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
 * Merges each Pause with the Resume that immediately follows it, so pausing and resuming becomes a
 * single editable/renderable stop instead of two markers/rows for the same physical stop. Grouping
 * is purely structural (adjacent Pause→Resume), never based on matching place names — a Pause that
 * happens to reverse-geocode to the same place as an earlier one (e.g. two separate stops back home
 * in the same city) is always its own stop. Both the edit screen and [RideRenderDataAssembler] must
 * use this same grouping so their stop indices line up.
 */
object StopGrouping {

    fun merge(stops: List<RideEvent>): List<MergedStop> {
        val result = mutableListOf<MergedStop>()
        for (stop in stops) {
            val last = result.lastOrNull()
            val closesLastPause = stop.action == RideEventAction.RESUME &&
                last != null && last.actions.last() == RideEventAction.PAUSE
            if (closesLastPause) {
                result[result.lastIndex] = last!!.copy(actions = last.actions + stop.action)
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
}
