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
    val startTime: Long,
    val endTime: Long?,
    val status: RideStatus,
    val totalDistanceM: Double,
    val totalDurationS: Long
)

/** Snapshot the UI observes; updated live while a ride is tracking or paused. */
data class RideUiState(
    val rideId: Long? = null,
    val status: RideStatus? = null,
    val distanceM: Double = 0.0,
    val durationS: Long = 0L
) {
    val isActive: Boolean get() = status == RideStatus.TRACKING || status == RideStatus.PAUSED
}
