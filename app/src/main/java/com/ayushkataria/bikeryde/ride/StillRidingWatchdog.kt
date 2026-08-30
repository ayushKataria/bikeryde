package com.ayushkataria.bikeryde.ride

import android.os.SystemClock

/**
 * Tracks whether an active day-segment has gone [THRESHOLD_MS] without a GPS-confirmed movement —
 * design doc §5.2/§6's "still riding?" safety check, for a rider who leaves tracking running
 * through a long unplanned stop (lunch, a breakdown, forgetting to pause) without noticing.
 * [recordMovement] resets the clock on every location update; [checkStillness] returns true the
 * moment the threshold is first crossed, then stays quiet — no repeat notification spam — until
 * movement resumes.
 */
class StillRidingWatchdog {
    private var lastMovementElapsedRealtime: Long = SystemClock.elapsedRealtime()
    private var alreadyNotified = false

    fun recordMovement() {
        lastMovementElapsedRealtime = SystemClock.elapsedRealtime()
        alreadyNotified = false
    }

    fun checkStillness(): Boolean {
        if (alreadyNotified) return false
        if (SystemClock.elapsedRealtime() - lastMovementElapsedRealtime < THRESHOLD_MS) return false
        alreadyNotified = true
        return true
    }

    companion object {
        private const val THRESHOLD_MS = 30 * 60 * 1000L
    }
}
