package com.ayushkataria.bikeryde.media

import com.ayushkataria.bikeryde.ride.RideRepository

/**
 * The single source of truth for a ride's full [MergedStop] sequence — every day's stops merged
 * (via [StopGrouping.merge]) one day at a time, then concatenated in day order. Merging per day
 * rather than over one ride-wide flat list produces an identical result (a day's own stop sequence
 * always starts with START and ends with END, so a merge can never reach across a day boundary
 * anyway) while also tagging each [MergedStop] with the [MergedStop.dayIndex] it belongs to — which
 * [RideRenderDataAssembler] needs to split the render into one [RenderDay] per actual
 * [com.ayushkataria.bikeryde.ride.RideDay]. Both [com.ayushkataria.bikeryde.ui.render.RenderEditFragment]
 * and [RideRenderDataAssembler] must call this same function so their stop indices line up.
 */
suspend fun mergedStopsForRide(repository: RideRepository, rideId: Long): List<MergedStop> =
    repository.getRideDays(rideId).flatMap { day ->
        StopGrouping.merge(repository.getEventsForDay(day.id), day.dayIndex)
    }
