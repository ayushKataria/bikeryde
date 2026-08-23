package com.ayushkataria.bikeryde.ride

/** Whether a [Ride] is a single-day outing or one leg of a longer trip. */
enum class RideType {
    SINGLE_DAY,
    MULTI_DAY
}

/**
 * One day within a multi-day [Ride]. A single-day ride has exactly one of these.
 * Not yet persisted anywhere — modeled ahead of the multi-day tracking feature (design doc §5.2).
 */
data class RideDay(
    val id: Long,
    val rideId: Long,
    val dayIndex: Int,
    val startTime: Long,
    val endTime: Long?,
    val startPlaceName: String?,
    val endPlaceName: String?,
    val distanceKm: Double,
    val durationS: Long
)

/** A manual start/pause/end action captured during a [RideDay], reverse-geocoded to a place name. */
data class Stop(
    val id: Long,
    val rideDayId: Long,
    val action: RideEventAction,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val placeName: String?
)

/** A single logged GPS fix belonging to a [RideDay]'s tracked route. */
data class GpsPoint(
    val id: Long,
    val rideDayId: Long,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val elevation: Double?,
    val speed: Float?
)
