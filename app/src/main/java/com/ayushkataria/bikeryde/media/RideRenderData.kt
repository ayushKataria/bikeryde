package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RidePoint

/**
 * A [com.ayushkataria.bikeryde.ride.Stop] resolved for rendering: [displayName] is the user's
 * edited label if they set one on the customize screen, otherwise the recorded place name, and
 * [backgroundImagePath] is the photo (if any) assigned to this stop for the video's crossfade.
 */
data class RenderStop(
    val action: RideEventAction,
    val timestamp: Long,
    val lat: Double?,
    val lng: Double?,
    val displayName: String?,
    val backgroundImagePath: String? = null,
    /** Which day this stop falls on — see [RenderDay.dayIndex]. */
    val dayIndex: Int = 0
)

/**
 * One day's worth of route to draw. A single-day ride produces exactly one of these; a multi-day
 * ride produces one per travel [com.ayushkataria.bikeryde.ride.RideDay] (rest days have no GPS
 * track and so contribute none) — [RouteFrameDrawer] stitches however many it's given end to end
 * and tags each day's start/end [RenderStop.displayName] with its day number.
 */
data class RenderDay(
    val dayIndex: Int,
    val label: String,
    val points: List<RidePoint>,
    val stops: List<RenderStop>
)

/**
 * Everything [RouteFrameDrawer] needs to render a ride's route and stats, independent of day count.
 *
 * [coverImagePath] is the single background photo for a static image; a video ignores it and
 * instead crossfades between each [RenderStop.backgroundImagePath] as the route animates past it —
 * the two are mutually exclusive per the customize screen (one photo for a still image, one per
 * stop for an animation).
 */
data class RideRenderData(
    val title: String,
    val totalDistanceM: Double,
    val totalDurationS: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double?,
    val days: List<RenderDay>,
    val coverImagePath: String? = null
) {
    /** All GPS points across all days, in order — the full route shape to draw. */
    val allPoints: List<RidePoint> get() = days.flatMap { it.points }
    val allStops: List<RenderStop> get() = days.flatMap { it.stops }
}
