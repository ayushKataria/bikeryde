package com.ayushkataria.bikeryde.ride

/**
 * Snapshot the multi-day ride screen observes, published live by [MultiDayRideTrackingService]
 * while a day-segment is open. Unlike [RideUiState], this only ever describes *today* — the
 * service may be recreated between days (the trip can easily span an app restart or several), so
 * trip-wide totals are re-derived from [RideRepository.getTripTotalsSoFar] by the screen itself
 * rather than tracked here.
 */
data class MultiDayRideUiState(
    val rideId: Long? = null,
    val rideDayId: Long? = null,
    val dayIndex: Int = 0,
    /** null when no day is currently open — the trip is between days, or hasn't started. */
    val dayStatus: RideStatus? = null,
    val todayDistanceM: Double = 0.0,
    val todayDurationS: Long = 0L,
    val todayTotalTimeS: Long = 0L,
    /** True while a start/pause/resume/finish-day/start-next-day/end-trip request is in flight. */
    val isBusy: Boolean = false
) {
    val isDayActive: Boolean get() = dayStatus == RideStatus.TRACKING || dayStatus == RideStatus.PAUSED
}
