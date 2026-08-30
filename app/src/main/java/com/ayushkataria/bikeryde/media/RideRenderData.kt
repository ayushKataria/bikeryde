package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RidePoint

/**
 * A [com.ayushkataria.bikeryde.ride.Stop] resolved for rendering: [displayName] is the user's
 * edited label if they set one on the customize screen, otherwise the recorded place name. On a
 * single-day ride, [backgroundImagePath] is the photo (if any) assigned to this specific stop for
 * the video's crossfade; on a multi-day ride, photos are assigned per [RenderDay] instead (see
 * [RenderDay.backgroundImagePath]), so this is always null there.
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
 * One day's worth of route to draw. A single-day ride produces exactly one of these (always
 * [RideDayType.TRAVEL]); a multi-day ride produces one per [com.ayushkataria.bikeryde.ride.RideDay],
 * travel *and* rest — a rest day has no [points]/[stops] of its own (nothing to draw a route with)
 * but still gets its own video segment: a held frame showing [backgroundImagePath]/[caption] for a
 * few seconds before the next day begins, per design doc §5.3's "labeled pause" treatment.
 *
 * [caption] is the rider-editable "Instagram style" bottom-of-frame line (e.g. "Day 2 - Sightseeing
 * in Bengaluru") — null when the customize screen's day-labels toggle is off, in which case nothing
 * is drawn for it. It's deliberately separate from any stop's place name.
 */
data class RenderDay(
    val dayIndex: Int,
    val dayType: RideDayType,
    val label: String,
    val points: List<RidePoint>,
    val stops: List<RenderStop>,
    val backgroundImagePath: String? = null,
    val caption: String? = null
)

/**
 * Everything [RouteFrameDrawer] needs to render a ride's route and stats, independent of day count.
 *
 * [coverImagePath] is the single background photo for a static image — a video ignores it. A
 * single-day video instead crossfades between each [RenderStop.backgroundImagePath] as the route
 * animates past it; a multi-day video crossfades between each [RenderDay.backgroundImagePath],
 * holding on a rest day's photo for a few seconds rather than animating through it.
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
    /** All GPS points across every travel day, in order — the full route shape to draw. Rest days
     * contribute nothing here (they have none), so point indices only ever refer to travel days. */
    val allPoints: List<RidePoint> get() = days.flatMap { it.points }
    val allStops: List<RenderStop> get() = days.flatMap { it.stops }
    val isMultiDay: Boolean get() = days.size > 1
}
