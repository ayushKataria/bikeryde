package com.ayushkataria.bikeryde.media

import kotlin.math.roundToInt

/**
 * Suggests an animation length long enough to let each stop's label (and crossfaded photo, if
 * any) actually register, without running past what's comfortable to share on social media (a
 * ride with many stops would otherwise stretch a fixed-length animation thin, while a short hop
 * doesn't need much time at all).
 */
object VideoDurationRecommender {
    private const val BASE_SECONDS = 10
    private const val SECONDS_PER_STOP = 3

    /** Hard bounds on the final duration, after the user's multiplier is applied. */
    const val MIN_SECONDS = 8
    const val MAX_SECONDS = 50

    const val MIN_MULTIPLIER = 0.5f
    const val MAX_MULTIPLIER = 2.0f
    const val DEFAULT_MULTIPLIER = 1.0f

    /** The recommended length (multiplier = 1.0x) for a ride with [stopCount] stops. */
    fun recommend(stopCount: Int): Int =
        (BASE_SECONDS + stopCount * SECONDS_PER_STOP).coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** The final length to render, applying the user's chosen multiplier and re-clamping to the social-friendly range. */
    fun apply(recommendedSeconds: Int, multiplier: Float): Int =
        (recommendedSeconds * multiplier).roundToInt().coerceIn(MIN_SECONDS, MAX_SECONDS)
}
