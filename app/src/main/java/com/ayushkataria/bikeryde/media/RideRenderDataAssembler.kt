package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds the [RideRenderData] for a ride — one [RenderDay] per [RideDay] (travel *and* rest), so a
 * multi-day trip's video/image shows its route, photos, and captions day by day instead of one
 * undifferentiated line.
 */
class RideRenderDataAssembler(private val rideRepository: RideRepository) {

    /**
     * @param stopNameOverrides custom labels from the customize screen, keyed by each merged
     *   stop's position in [mergedStopsForRide]'s (chronological) order — a Start immediately
     *   followed by a Pause at the same place is one entry, not two. Empty/absent falls back to
     *   the recorded place name.
     * @param stopBackgroundPaths per-merged-stop background photo paths, same keying — single-day
     *   video only; a multi-day video uses [dayBackgroundPaths] instead (see that param's kdoc).
     * @param excludedStopIndices merged-stop positions (same keying) the rider unchecked on the
     *   customize screen — dropped entirely, so neither the image nor the video shows a
     *   marker/label (or crossfades to a photo) for them.
     * @param coverImagePath the single background photo for a static image.
     * @param dayBackgroundPaths one background photo per day, keyed by [RideDay.dayIndex] —
     *   replaces per-stop photos entirely for a multi-day video (there's no per-stop photo picker
     *   on the customize screen once a ride has more than one day).
     * @param dayCaptionOverrides rider-edited "Instagram style" caption per day, same keying as
     *   [dayBackgroundPaths]. Only used when [dayLabelsEnabled] is true; a day with no override
     *   here falls back to [defaultDayCaption].
     * @param dayLabelsEnabled the customize screen's "Add day labels" checkbox — when false, every
     *   [RenderDay.caption] is null and nothing is drawn for it, regardless of [dayCaptionOverrides].
     */
    suspend fun assemble(
        rideId: Long,
        stopNameOverrides: Map<Int, String> = emptyMap(),
        stopBackgroundPaths: Map<Int, String> = emptyMap(),
        excludedStopIndices: Set<Int> = emptySet(),
        coverImagePath: String? = null,
        dayBackgroundPaths: Map<Int, String> = emptyMap(),
        dayCaptionOverrides: Map<Int, String> = emptyMap(),
        dayLabelsEnabled: Boolean = false
    ): RideRenderData? {
        val ride = rideRepository.getRide(rideId) ?: return null
        val rideDays = rideRepository.getRideDays(rideId)
        val isMultiDay = rideDays.size > 1
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
                // A multi-day ride assigns photos per day, not per stop — see dayBackgroundPaths.
                backgroundImagePath = if (isMultiDay) null else stopBackgroundPaths[index],
                dayIndex = stop.dayIndex
            )
        }

        val renderDays = rideDays.map { day ->
            RenderDay(
                dayIndex = day.dayIndex,
                dayType = day.dayType,
                label = dayTaggedLabel(day.dayIndex, dateFormat.format(day.startTime)),
                points = if (day.dayType == RideDayType.TRAVEL) rideRepository.getRoutePointsForDay(day.id) else emptyList(),
                stops = renderStops.filter { it.dayIndex == day.dayIndex },
                backgroundImagePath = if (isMultiDay) dayBackgroundPaths[day.dayIndex] else null,
                caption = if (isMultiDay && dayLabelsEnabled) {
                    dayCaptionOverrides[day.dayIndex]?.takeIf { it.isNotBlank() } ?: defaultDayCaption(day)
                } else {
                    null
                }
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

    /**
     * The customize screen's starting point for a day's caption, before the rider edits it: the
     * first day just names where it ended up ("Day 1 - Bengaluru"), a later travel day frames it as
     * arriving somewhere ("Day 3 - Travel to Hyderabad"), and a rest day calls out what it was for
     * ("Day 2 - Sightseeing in Bengaluru").
     */
    fun defaultDayCaption(day: RideDay): String {
        val place = day.endPlaceName ?: day.startPlaceName
        val description = when {
            day.dayType == RideDayType.NOT_TRAVEL -> place?.let { "Sightseeing in $it" } ?: "Sightseeing"
            day.dayIndex == 0 -> place
            else -> place?.let { "Travel to $it" } ?: "Travel"
        }
        return "Day ${day.dayIndex + 1}" + (description?.let { " - $it" } ?: "")
    }

    /** "Day N · place" — the map-marker label style, distinct from [defaultDayCaption]'s "Day N -
     * place" caption style so the two visually different UI elements don't read as the same thing. */
    private fun dayTaggedLabel(dayIndex: Int, base: String?): String =
        if (base.isNullOrBlank()) "Day ${dayIndex + 1}" else "Day ${dayIndex + 1} · $base"
}
