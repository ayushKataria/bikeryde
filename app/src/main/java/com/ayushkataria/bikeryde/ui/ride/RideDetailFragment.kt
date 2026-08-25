package com.ayushkataria.bikeryde.ui.ride

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import com.ayushkataria.bikeryde.ui.render.RenderLauncher
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Read-only view of a completed ride opened from [com.ayushkataria.bikeryde.ui.history.RideHistoryFragment]
 * — the same route/stats presentation as the live tracking screen's post-ride state, minus the
 * start/pause/end controls, plus the ability to (re)create its static image or animation.
 */
class RideDetailFragment : Fragment(R.layout.fragment_ride_detail) {

    private lateinit var repository: RideRepository
    private lateinit var legendBinder: StopsLegendBinder

    private lateinit var distanceText: TextView
    private lateinit var durationText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var avgSpeedText: TextView
    private lateinit var maxSpeedText: TextView
    private lateinit var stopsCountText: TextView
    private lateinit var routeMapView: RouteMapView
    private lateinit var stopsLegend: LinearLayout
    private lateinit var createImageButton: MaterialButton
    private lateinit var createAnimationButton: MaterialButton

    private var rideId: Long = -1L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rideId = requireArguments().getLong(ARG_RIDE_ID)
        repository = RideRepository(requireContext())

        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        distanceText = view.findViewById(R.id.distanceText)
        durationText = view.findViewById(R.id.durationText)
        totalTimeText = view.findViewById(R.id.totalTimeText)
        avgSpeedText = view.findViewById(R.id.avgSpeedText)
        maxSpeedText = view.findViewById(R.id.maxSpeedText)
        stopsCountText = view.findViewById(R.id.stopsCountText)
        routeMapView = view.findViewById(R.id.routeMapView)
        stopsLegend = view.findViewById(R.id.stopsLegend)
        createImageButton = view.findViewById(R.id.createImageButton)
        createAnimationButton = view.findViewById(R.id.createAnimationButton)

        legendBinder = StopsLegendBinder(requireContext(), stopsLegend)
        routeMapView.setRouteColor(MaterialColors.getColor(routeMapView, com.google.android.material.R.attr.colorPrimary))
        routeMapView.setEmptyTextColor(
            MaterialColors.getColor(routeMapView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        routeMapView.setMarkerColors(
            start = legendBinder.colorForStopAction(RideEventAction.START),
            pauseResume = legendBinder.colorForStopAction(RideEventAction.PAUSE),
            end = legendBinder.colorForStopAction(RideEventAction.END)
        )

        createImageButton.setOnClickListener { RenderLauncher.open(this, rideId, RenderType.IMAGE) }
        createAnimationButton.setOnClickListener { RenderLauncher.open(this, rideId, RenderType.VIDEO) }

        loadRide(view)
    }

    private fun loadRide(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ride = repository.getRide(rideId) ?: return@launch
            val points = repository.getRoutePoints(rideId)
            val events = repository.getEvents(rideId)
            val maxSpeedMps = repository.getMaxSpeedMps(rideId)

            view.findViewById<TextView>(R.id.topBarTitle).text =
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(ride.startTime)

            distanceText.text = RideStatsFormat.distance(ride.totalDistanceM)
            durationText.text = RideStatsFormat.duration(ride.totalDurationS)
            val totalTimeS = ride.endTime?.let { (it - ride.startTime) / 1000 } ?: ride.totalDurationS
            totalTimeText.text = RideStatsFormat.duration(totalTimeS)

            val avgSpeedKmh = if (ride.totalDurationS > 0) (ride.totalDistanceM / ride.totalDurationS) * 3.6 else 0.0
            avgSpeedText.text = formatSpeed(avgSpeedKmh)
            maxSpeedText.text = maxSpeedMps?.let { formatSpeed(it * 3.6) } ?: getString(R.string.value_placeholder_speed)
            stopsCountText.text = events.size.toString()

            routeMapView.submit(points, events)
            legendBinder.render(events)
        }
    }

    private fun formatSpeed(kmh: Double): String =
        getString(R.string.speed_unit_format, String.format(Locale.US, "%.1f", kmh))

    companion object {
        const val ARG_RIDE_ID = "rideId"

        fun args(rideId: Long): Bundle = bundleOf(ARG_RIDE_ID to rideId)
    }
}
