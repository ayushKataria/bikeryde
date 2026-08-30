package com.ayushkataria.bikeryde.ride

/** Lifecycle state of a single-day ride. */
enum class RideStatus {
    TRACKING,
    PAUSED,
    COMPLETED
}

/** A manual, user-triggered ride control action, recorded with a timestamp and location. */
enum class RideEventAction {
    START,
    PAUSE,
    RESUME,
    END
}

data class Ride(
    val id: Long,
    val type: RideType,
    val startTime: Long,
    val endTime: Long?,
    val status: RideStatus,
    val totalDistanceM: Double,
    val totalDurationS: Long,
    /** User-given name, set via the rename action on the ride history screen — null falls back to the ride's date. */
    val title: String? = null
)

/** A single logged GPS fix for the live route render — lighter-weight than the full [GpsPoint] model. */
data class RidePoint(
    val lat: Double,
    val lng: Double,
    val speedMps: Float?
)

/** A start/pause/resume/end control action with its reverse-geocoded place name, for the route's
 * stop markers. [id] is the underlying `stop` row's id — needed to permanently rename its place. */
data class RideEvent(
    val id: Long,
    val action: RideEventAction,
    val timestamp: Long,
    val lat: Double?,
    val lng: Double?,
    val placeName: String?
)

/** Snapshot the UI observes; updated live while a ride is tracking or paused. */
data class RideUiState(
    val rideId: Long? = null,
    val status: RideStatus? = null,
    val distanceM: Double = 0.0,
    val durationS: Long = 0L,
    /** Wall-clock time from ride start to now (or to end, once completed) — unlike [durationS], this includes pauses. */
    val totalTimeS: Long = 0L,
    /** True while a start/pause/resume/end action is in flight (GPS fix + reverse geocode + DB write) — the UI should disable ride controls and show a loading indicator while this is true, so a slow network/GPS fix can't be double-tapped into duplicate actions. */
    val isBusy: Boolean = false
) {
    val isActive: Boolean get() = status == RideStatus.TRACKING || status == RideStatus.PAUSED
}
