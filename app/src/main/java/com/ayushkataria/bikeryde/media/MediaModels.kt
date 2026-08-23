package com.ayushkataria.bikeryde.media

/** A user-added photo, optionally usable as a background layer during video compositing. */
data class Photo(
    val id: Long,
    val rideId: Long,
    val filePath: String,
    val timestamp: Long,
    val lat: Double?,
    val lng: Double?,
    val usedAsBackground: Boolean
)

enum class RenderType {
    IMAGE,
    VIDEO
}

enum class RenderStatus {
    QUEUED,
    PROCESSING,
    DONE,
    FAILED
}

/** A queued or completed static-image / animated-video export for a ride (design doc §5.3). */
data class Render(
    val id: Long,
    val rideId: Long,
    val type: RenderType,
    val status: RenderStatus,
    val resolution: String,
    val fps: Int,
    val filePath: String?,
    val createdAt: Long
)
