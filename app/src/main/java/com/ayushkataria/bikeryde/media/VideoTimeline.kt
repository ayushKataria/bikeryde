package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideDayType

/**
 * One frame's worth of render state. [progress] drives the route/stats sweep — 0..1 across every
 * travel day's points, concatenated, exactly as it always has (frozen during a rest day's hold).
 *
 * [dayIndex] and [daySegmentProgress] are a *separate* timeline, in real playback time rather than
 * GPS-point-index space: every day, travel or rest, is a "segment" with its own 0..1 progress, so
 * the day's photo/caption can fade in the same way regardless of day type. Point-index space can't
 * represent this on its own — a rest day contributes zero points, so it has zero width there, which
 * is exactly why the crossfade used to jump straight from one travel day's photo to the next one's,
 * skipping over (and hard-cutting into/out of) any rest day between them.
 */
data class RenderFrame(val progress: Float, val dayIndex: Int, val daySegmentProgress: Float)

/**
 * Builds a video's full frame-by-frame timeline: the same even sweep across every travel day's
 * points a single-day ride has always used, with a fixed-length hold spliced in wherever a rest day
 * falls in the trip — design doc §5.3's "labeled pause… rather than being skipped" for a
 * [RideDayType.NOT_TRAVEL] day. A hold's length is added on top of the rider's chosen animation
 * length, the same way the existing end-of-video linger already is.
 */
object VideoTimeline {

    /** How long the video holds on a rest day's photo/caption before continuing to the next day. */
    const val REST_DAY_HOLD_SECONDS = 3

    private class TravelSpan(val dayIndex: Int, val startPoint: Int, val endPoint: Int)

    fun build(data: RideRenderData, fps: Int, animationFrameCount: Int, lingerFrameCount: Int): List<RenderFrame> {
        val travelSpans = mutableListOf<TravelSpan>()
        var cursor = 0
        for (day in data.days) {
            if (day.dayType == RideDayType.TRAVEL) {
                val start = cursor
                cursor += day.points.size
                travelSpans += TravelSpan(day.dayIndex, start, (cursor - 1).coerceAtLeast(start))
            }
        }
        val totalTravelPoints = cursor
        val lastDayIndex = data.days.maxByOrNull { it.dayIndex }?.dayIndex ?: 0

        val mainFrames = (0 until animationFrameCount).map { frame ->
            val progress = if (animationFrameCount > 1) frame / (animationFrameCount - 1).toFloat() else 1f
            val pointIndex = progress * (totalTravelPoints - 1).coerceAtLeast(0)
            val span = travelSpans.lastOrNull { it.startPoint <= pointIndex } ?: travelSpans.firstOrNull()
            val dayProgress = if (span == null) {
                1f
            } else {
                val spanLength = (span.endPoint - span.startPoint).coerceAtLeast(1)
                ((pointIndex - span.startPoint) / spanLength).coerceIn(0f, 1f)
            }
            RenderFrame(progress = progress, dayIndex = span?.dayIndex ?: lastDayIndex, daySegmentProgress = dayProgress)
        }

        val restHoldFrameCount = fps * REST_DAY_HOLD_SECONDS
        val timeline = mutableListOf<RenderFrame>()
        var travelPointsSoFar = 0
        var mainFramesEmitted = 0

        for (day in data.days) {
            if (day.dayType == RideDayType.NOT_TRAVEL) {
                val frozenProgress = timeline.lastOrNull()?.progress ?: 0f
                for (i in 0 until restHoldFrameCount) {
                    val holdProgress = if (restHoldFrameCount > 1) i / (restHoldFrameCount - 1).toFloat() else 1f
                    timeline += RenderFrame(frozenProgress, dayIndex = day.dayIndex, daySegmentProgress = holdProgress)
                }
            } else {
                travelPointsSoFar += day.points.size
                val targetFraction = if (totalTravelPoints > 1) {
                    travelPointsSoFar / (totalTravelPoints - 1).toFloat()
                } else {
                    1f
                }
                val targetFrameIndex = (targetFraction * (animationFrameCount - 1))
                    .toInt()
                    .coerceIn(0, (animationFrameCount - 1).coerceAtLeast(0))
                while (mainFramesEmitted <= targetFrameIndex && mainFramesEmitted < mainFrames.size) {
                    timeline += mainFrames[mainFramesEmitted]
                    mainFramesEmitted++
                }
            }
        }
        while (mainFramesEmitted < mainFrames.size) {
            timeline += mainFrames[mainFramesEmitted]
            mainFramesEmitted++
        }

        repeat(lingerFrameCount) { timeline += RenderFrame(progress = 1f, dayIndex = lastDayIndex, daySegmentProgress = 1f) }
        return timeline
    }
}
