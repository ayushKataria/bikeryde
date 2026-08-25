package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideRepository
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds the [RideRenderData] for a ride. Today every ride is single-day, so this always produces
 * one [RenderDay] covering the whole route. Once multi-day tracking exists, this is the only place
 * that needs to change — swap the single [RideDay] lookup for [RideRepository.getRideDays] and
 * build one [RenderDay] per row — [RouteFrameDrawer], [StaticImageRenderer] and [VideoRenderWorker]
 * all already operate on the day list, not on a single-day assumption.
 */
class RideRenderDataAssembler(private val rideRepository: RideRepository) {

    /**
     * @param stopNameOverrides custom labels from the customize screen, keyed by each merged
     *   stop's position in [StopGrouping.merge]'s (chronological) order — a Start immediately
     *   followed by a Pause at the same place is one entry, not two. Empty/absent falls back to
     *   the recorded place name.
     * @param stopBackgroundPaths per-merged-stop background photo paths, same keying, video only.
     * @param coverImagePath the single background photo for a static image.
     */
    suspend fun assemble(
        rideId: Long,
        stopNameOverrides: Map<Int, String> = emptyMap(),
        stopBackgroundPaths: Map<Int, String> = emptyMap(),
        coverImagePath: String? = null
    ): RideRenderData? {
        val ride = rideRepository.getRide(rideId) ?: return null
        val points = rideRepository.getRoutePoints(rideId)
        val mergedStops = StopGrouping.merge(rideRepository.getEvents(rideId))
        val durationS = ride.totalDurationS
        val avgSpeedKmh = if (durationS > 0) (ride.totalDistanceM / durationS) * 3.6 else 0.0
        val maxSpeedKmh = rideRepository.getMaxSpeedMps(rideId)?.let { it * 3.6 }
        val title = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(ride.startTime)

        val renderStops = mergedStops.mapIndexed { index, stop ->
            RenderStop(
                action = stop.primaryAction,
                timestamp = stop.timestamp,
                lat = stop.lat,
                lng = stop.lng,
                displayName = stopNameOverrides[index]?.takeIf { it.isNotBlank() } ?: stop.placeName,
                backgroundImagePath = stopBackgroundPaths[index]
            )
        }

        return RideRenderData(
            title = title,
            totalDistanceM = ride.totalDistanceM,
            totalDurationS = durationS,
            avgSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            days = listOf(RenderDay(dayIndex = 0, label = title, points = points, stops = renderStops)),
            coverImagePath = coverImagePath
        )
    }
}
