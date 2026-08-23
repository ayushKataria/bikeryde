package com.ayushkataria.bikeryde.ui.ride

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isEmpty
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.RideEvent
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import com.ayushkataria.bikeryde.ride.RideStatus
import com.ayushkataria.bikeryde.ride.RideTrackingService
import com.ayushkataria.bikeryde.ride.RideTrackingState
import com.ayushkataria.bikeryde.ride.RideUiState
import com.ayushkataria.bikeryde.ui.placeholder.ComingSoonFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class SingleDayRideFragment : Fragment(R.layout.fragment_single_day_ride) {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var durationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var activeActionsRow: LinearLayout
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var postRideActionsRow: LinearLayout
    private lateinit var createImageButton: MaterialButton
    private lateinit var createAnimationButton: MaterialButton
    private lateinit var rideStatsSection: LinearLayout
    private lateinit var avgSpeedText: TextView
    private lateinit var maxSpeedText: TextView
    private lateinit var stopsCountText: TextView
    private lateinit var routeMapView: RouteMapView
    private lateinit var stopsLegend: LinearLayout

    private lateinit var repository: RideRepository

    private var pendingStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted && pendingStart) {
            pendingStart = false
            RideTrackingService.start(requireContext())
        } else if (!locationGranted) {
            pendingStart = false
            Toast.makeText(requireContext(), R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.home_card_single_day_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener {
            findNavController().navigateUp()
        }

        statusDot = view.findViewById(R.id.statusDot)
        statusText = view.findViewById(R.id.statusText)
        durationText = view.findViewById(R.id.durationText)
        distanceText = view.findViewById(R.id.distanceText)
        startButton = view.findViewById(R.id.startButton)
        activeActionsRow = view.findViewById(R.id.activeActionsRow)
        pauseResumeButton = view.findViewById(R.id.pauseResumeButton)
        endButton = view.findViewById(R.id.endButton)
        postRideActionsRow = view.findViewById(R.id.postRideActionsRow)
        createImageButton = view.findViewById(R.id.createImageButton)
        createAnimationButton = view.findViewById(R.id.createAnimationButton)
        rideStatsSection = view.findViewById(R.id.rideStatsSection)
        avgSpeedText = view.findViewById(R.id.avgSpeedText)
        maxSpeedText = view.findViewById(R.id.maxSpeedText)
        stopsCountText = view.findViewById(R.id.stopsCountText)
        routeMapView = view.findViewById(R.id.routeMapView)
        stopsLegend = view.findViewById(R.id.stopsLegend)

        routeMapView.setRouteColor(MaterialColors.getColor(routeMapView, com.google.android.material.R.attr.colorPrimary))
        routeMapView.setEmptyTextColor(
            MaterialColors.getColor(routeMapView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        routeMapView.setMarkerColors(
            start = colorForStopAction(RideEventAction.START),
            pauseResume = colorForStopAction(RideEventAction.PAUSE),
            end = colorForStopAction(RideEventAction.END)
        )
        repository = RideRepository(requireContext())

        startButton.setOnClickListener { onStartClicked() }
        pauseResumeButton.setOnClickListener { onPauseResumeClicked() }
        endButton.setOnClickListener { onEndClicked() }
        createImageButton.setOnClickListener {
            navigateToComingSoon(R.string.post_ride_create_image, R.string.coming_soon_desc_static_image)
        }
        createAnimationButton.setOnClickListener {
            navigateToComingSoon(R.string.post_ride_create_animation, R.string.coming_soon_desc_animation)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideTrackingState.state.collect { render(it) }
            }
        }
    }

    private fun onStartClicked() {
        val context = requireContext()
        val permissionsNeeded = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            RideTrackingService.start(context)
        } else {
            pendingStart = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPauseResumeClicked() {
        when (RideTrackingState.state.value.status) {
            RideStatus.TRACKING -> RideTrackingService.pause(requireContext())
            RideStatus.PAUSED -> RideTrackingService.resume(requireContext())
            else -> Unit
        }
    }

    private fun onEndClicked() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.end_ride_confirm_title)
            .setMessage(R.string.end_ride_confirm_message)
            .setPositiveButton(R.string.action_end) { _, _ -> RideTrackingService.end(requireContext()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(state: RideUiState) {
        durationText.text = formatDuration(state.durationS)
        distanceText.text = formatDistance(state.distanceM)

        val (statusLabel, statusColorRes) = when (state.status) {
            RideStatus.TRACKING -> R.string.status_tracking to R.color.status_tracking
            RideStatus.PAUSED -> R.string.status_paused to R.color.status_paused
            RideStatus.COMPLETED -> R.string.status_completed to R.color.status_completed
            null -> R.string.status_idle to R.color.status_idle
        }
        statusText.setText(statusLabel)
        val statusColor = ContextCompat.getColor(requireContext(), statusColorRes)
        statusText.setTextColor(statusColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)

        val isActive = state.status == RideStatus.TRACKING || state.status == RideStatus.PAUSED
        startButton.visibility = if (isActive) View.GONE else View.VISIBLE
        activeActionsRow.visibility = if (isActive) View.VISIBLE else View.GONE
        postRideActionsRow.visibility = if (state.status == RideStatus.COMPLETED) View.VISIBLE else View.GONE
        if (state.status == RideStatus.PAUSED) {
            pauseResumeButton.setText(R.string.action_resume)
            pauseResumeButton.setIconResource(R.drawable.ic_play)
        } else {
            pauseResumeButton.setText(R.string.action_pause)
            pauseResumeButton.setIconResource(R.drawable.ic_pause)
        }

        val rideId = state.rideId
        rideStatsSection.visibility = if (rideId != null) View.VISIBLE else View.GONE
        if (rideId != null) {
            refreshRideStats(rideId, state.distanceM, state.durationS)
        }
    }

    private fun refreshRideStats(rideId: Long, distanceM: Double, durationS: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val points = repository.getRoutePoints(rideId)
            val events = repository.getEvents(rideId)
            val maxSpeedMps = repository.getMaxSpeedMps(rideId)

            routeMapView.submit(points, events)

            val avgSpeedKmh = if (durationS > 0) (distanceM / durationS) * 3.6 else 0.0
            avgSpeedText.text = formatSpeed(avgSpeedKmh)
            maxSpeedText.text = maxSpeedMps?.let { formatSpeed(it * 3.6) } ?: getString(R.string.value_placeholder_speed)
            stopsCountText.text = events.size.toString()

            renderStopsLegend(events)
        }
    }

    private fun renderStopsLegend(events: List<RideEvent>) {
        stopsLegend.removeAllViews()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        events.forEach { event ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (stopsLegend.isEmpty()) 0 else dpToPx(10) }
            }
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_dot)
                backgroundTintList = ColorStateList.valueOf(colorForStopAction(event.action))
            }
            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(10)
                }
                text = getString(
                    R.string.stop_legend_row_format,
                    stopActionLabel(event.action),
                    event.placeName ?: getString(R.string.unknown_location),
                    timeFormat.format(event.timestamp)
                )
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                textSize = 14f
            }
            row.addView(dot)
            row.addView(label)
            stopsLegend.addView(row)
        }
    }

    private fun stopActionLabel(action: RideEventAction): String = when (action) {
        RideEventAction.START -> getString(R.string.stop_action_start)
        RideEventAction.PAUSE -> getString(R.string.stop_action_pause)
        RideEventAction.RESUME -> getString(R.string.stop_action_resume)
        RideEventAction.END -> getString(R.string.stop_action_end)
    }

    private fun colorForStopAction(action: RideEventAction): Int = ContextCompat.getColor(
        requireContext(),
        when (action) {
            RideEventAction.START -> R.color.status_tracking
            RideEventAction.END -> R.color.md_error
            RideEventAction.PAUSE, RideEventAction.RESUME -> R.color.status_paused
        }
    )

    private fun formatSpeed(kmh: Double): String = getString(R.string.speed_unit_format, String.format(Locale.US, "%.1f", kmh))

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatDistance(distanceM: Double): String =
        String.format(Locale.US, "%.2f km", distanceM / 1000.0)

    private fun navigateToComingSoon(titleRes: Int, descriptionRes: Int) {
        findNavController().navigate(
            R.id.comingSoonFragment,
            bundleOf(
                ComingSoonFragment.ARG_TITLE to getString(titleRes),
                ComingSoonFragment.ARG_DESCRIPTION to getString(descriptionRes)
            )
        )
    }
}
