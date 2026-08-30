package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds the [RideRenderData] for a ride — one [RenderDay] per travel [RideDay], so a multi-day
 * trip's video/image shows its route day by day instead of one undifferentiated line.
 */
class RideRenderDataAssembler(private val rideRepository: RideRepository) {

    /**
     * @param stopNameOverrides custom labels from the customize screen, keyed by each merged
     *   stop's position in [mergedStopsForRide]'s (chronological) order — a Start immediately
     *   followed by a Pause at the same place is one entry, not two. Empty/absent falls back to
     *   the recorded place name.
     * @param stopBackgroundPaths per-merged-stop background photo paths, same keying, video only.
     * @param excludedStopIndices merged-stop positions (same keying) the rider unchecked on the
     *   customize screen — dropped entirely, so neither the image nor the video shows a
     *   marker/label (or crossfades to a photo) for them.
     * @param coverImagePath the single background photo for a static image.
     */
    suspend fun assemble(
        rideId: Long,
        stopNameOverrides: Map<Int, String> = emptyMap(),
        stopBackgroundPaths: Map<Int, String> = emptyMap(),
        excludedStopIndices: Set<Int> = emptySet(),
        coverImagePath: String? = null
    ): RideRenderData? {
        val ride = rideRepository.getRide(rideId) ?: return null
        val rideDays = rideRepository.getRideDays(rideId)
        val travelDays = rideDays.filter { it.dayType == RideDayType.TRAVEL }
        val isMultiDay = travelDays.size > 1
        val mergedStops = mergedStopsForRide(rideRepository, rideId)
        val durationS = ride.totalDurationS
        val avgSpeedKmh = if (durationS > 0) (ride.totalDistanceM / durationS) * 3.6 else 0.0
        val maxSpeedKmh = rideRepository.getMaxSpeedMps(rideId)?.let { it * 3.6 }
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val title = if (isMultiDay && ride.endTime != null) {
            "${dateFormat.format(ride.startTime)} – ${dateFormat.format(ride.endTime)}"
        } else {
            dateFormat.format(ride.startTime)
        }

        val renderStops = mergedStops.mapIndexedNotNull { index, stop ->
            if (index in excludedStopIndices) return@mapIndexedNotNull null
            val baseName = stopNameOverrides[index]?.takeIf { it.isNotBlank() } ?: stop.placeName
            // Only a day's own start/end get the day number — a mid-day pause doesn't need one,
            // since the color gradient and the day's own start/end labels already place it.
            val isDayBoundary = stop.primaryAction == RideEventAction.START || stop.actions.contains(RideEventAction.END)
            val displayName = if (isMultiDay && isDayBoundary) dayTaggedLabel(stop.dayIndex, baseName) else baseName
            RenderStop(
                action = stop.primaryAction,
                timestamp = stop.timestamp,
                lat = stop.lat,
                lng = stop.lng,
                displayName = displayName,
                backgroundImagePath = stopBackgroundPaths[index],
                dayIndex = stop.dayIndex
            )
        }

        val renderDays = travelDays.map { day ->
            RenderDay(
                dayIndex = day.dayIndex,
                label = dayTaggedLabel(day.dayIndex, dateFormat.format(day.startTime)),
                points = rideRepository.getRoutePointsForDay(day.id),
                stops = renderStops.filter { it.dayIndex == day.dayIndex }
            )
        }

        return RideRenderData(
            title = title,
            totalDistanceM = ride.totalDistanceM,
            totalDurationS = durationS,
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            days = renderDays,
            coverImagePath = coverImagePath
        )
    }

    private fun dayTaggedLabel(dayIndex: Int, base: String?): String =
        if (base.isNullOrBlank()) "Day ${dayIndex + 1}" else "Day ${dayIndex + 1} · $base"
}
