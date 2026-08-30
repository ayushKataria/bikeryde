package com.ayushkataria.bikeryde.ui.ride

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.MultiDayRideTrackingService
import com.ayushkataria.bikeryde.ride.MultiDayRideTrackingState
import com.ayushkataria.bikeryde.ride.MultiDayRideUiState
import com.ayushkataria.bikeryde.ride.Ride
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import com.ayushkataria.bikeryde.ride.RideStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

/**
 * The design doc's §5.2 multi-day flow: start a trip once, then start/pause/finish each day as it
 * comes, with a "start next day" possibly happening the next morning after this screen (and its
 * tracking service) has been closed and reopened. Because of that gap, this screen always re-derives
 * where the trip stands from the database ([RideRepository.getActiveTrip]/[RideRepository.getRideDays])
 * rather than trusting only the live [MultiDayRideTrackingState] — that's only authoritative for
 * *today's* ticking numbers, and only while it actually matches the day the database says is open.
 */
class MultiDayRideFragment : Fragment(R.layout.fragment_multi_day_ride) {

    private lateinit var repository: RideRepository

    private lateinit var idleSection: View
    private lateinit var tripSection: View
    private lateinit var startTripButton: MaterialButton
    private lateinit var dayLabelText: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var todayDurationText: TextView
    private lateinit var todayDistanceText: TextView
    private lateinit var actionProgress: CircularProgressIndicator
    private lateinit var dayOpenActionsRow: View
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var finishDayButton: MaterialButton
    private lateinit var betweenDaysActionsRow: View
    private lateinit var startNextDayButton: MaterialButton
    private lateinit var logRestDayButton: MaterialButton
    private lateinit var endTripButton: MaterialButton
    private lateinit var tripDistanceText: TextView
    private lateinit var tripDurationText: TextView
    private lateinit var routeMapView: RouteMapView
    private lateinit var stopsLegend: LinearLayout
    private lateinit var daysContainer: ViewGroup
    private lateinit var legendBinder: StopsLegendBinder

    /** Set by the latest [loadState] call so click handlers know what they're acting on. */
    private var activeTrip: Ride? = null
    private var openDay: RideDay? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (locationGranted) {
            action?.invoke()
        } else {
            Toast.makeText(requireContext(), R.string.multi_day_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.home_card_multi_day_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        repository = RideRepository(requireContext())

        idleSection = view.findViewById(R.id.idleSection)
        tripSection = view.findViewById(R.id.tripSection)
        startTripButton = view.findViewById(R.id.startTripButton)
        dayLabelText = view.findViewById(R.id.dayLabelText)
        statusDot = view.findViewById(R.id.statusDot)
        statusText = view.findViewById(R.id.statusText)
        todayDurationText = view.findViewById(R.id.todayDurationText)
        todayDistanceText = view.findViewById(R.id.todayDistanceText)
        actionProgress = view.findViewById(R.id.actionProgress)
        dayOpenActionsRow = view.findViewById(R.id.dayOpenActionsRow)
        pauseResumeButton = view.findViewById(R.id.pauseResumeButton)
        finishDayButton = view.findViewById(R.id.finishDayButton)
        betweenDaysActionsRow = view.findViewById(R.id.betweenDaysActionsRow)
        startNextDayButton = view.findViewById(R.id.startNextDayButton)
        logRestDayButton = view.findViewById(R.id.logRestDayButton)
        endTripButton = view.findViewById(R.id.endTripButton)
        tripDistanceText = view.findViewById(R.id.tripDistanceText)
        tripDurationText = view.findViewById(R.id.tripDurationText)
        routeMapView = view.findViewById(R.id.routeMapView)
        stopsLegend = view.findViewById(R.id.stopsLegend)
        daysContainer = view.findViewById(R.id.daysContainer)

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

        startTripButton.setOnClickListener { onStartTripClicked() }
        pauseResumeButton.setOnClickListener { onPauseResumeClicked() }
        finishDayButton.setOnClickListener { MultiDayRideTrackingService.finishDay(requireContext()) }
        startNextDayButton.setOnClickListener { onStartNextDayClicked() }
        logRestDayButton.setOnClickListener { onLogRestDayClicked() }
        endTripButton.setOnClickListener { onEndTripClicked() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MultiDayRideTrackingState.state.collect { loadState(it) }
            }
        }
    }

    private fun loadState(live: MultiDayRideUiState) {
        viewLifecycleOwner.lifecycleScope.launch {
            val trip = repository.getActiveTrip()
            activeTrip = trip
            if (trip == null) {
                openDay = null
                idleSection.visibility = View.VISIBLE
                tripSection.visibility = View.GONE
                return@launch
            }

            idleSection.visibility = View.GONE
            tripSection.visibility = View.VISIBLE

            val days = repository.getRideDays(trip.id)
            val latestDay = days.maxByOrNull { it.dayIndex }
            val currentOpenDay = latestDay?.takeIf { it.dayType == RideDayType.TRAVEL && it.endTime == null }
            openDay = currentOpenDay
            val liveMatches = currentOpenDay != null && live.rideId == trip.id && live.rideDayId == currentOpenDay.id
            val dayLiveState = if (liveMatches) live else null

            renderDayCard(currentOpenDay, latestDay, dayLiveState)
            renderActions(currentOpenDay, dayLiveState, latestDay)

            actionProgress.visibility = if (live.isBusy) View.VISIBLE else View.GONE
            startTripButton.isEnabled = !live.isBusy

            val points = repository.getRoutePoints(trip.id)
            val events = repository.getEvents(trip.id).filterNot { it.action == RideEventAction.RESUME }
            routeMapView.submit(points, events)
            legendBinder.render(events)

            val (finalizedDistanceM, finalizedDurationS) = repository.getTripTotalsSoFar(trip.id)
            val liveTodayDistance = dayLiveState?.todayDistanceM ?: 0.0
            val liveTodayDuration = dayLiveState?.todayDurationS ?: 0L
            tripDistanceText.text = RideStatsFormat.distance(finalizedDistanceM + liveTodayDistance)
            tripDurationText.text = RideStatsFormat.duration(finalizedDurationS + liveTodayDuration)

            daysContainer.removeAllViews()
            days.forEach { day -> daysContainer.addView(MultiDayRowBinder.buildRow(requireContext(), daysContainer, day)) }
        }
    }

    private fun renderDayCard(openDay: RideDay?, latestDay: RideDay?, dayLiveState: MultiDayRideUiState?) {
        val displayDayIndex = openDay?.dayIndex ?: ((latestDay?.dayIndex ?: -1) + 1)
        dayLabelText.text = getString(R.string.day_label_format, displayDayIndex + 1)

        val (statusLabel, statusColorRes) = when {
            openDay == null -> R.string.status_idle to R.color.status_idle
            dayLiveState?.dayStatus == RideStatus.TRACKING -> R.string.status_tracking to R.color.status_tracking
            else -> R.string.status_paused to R.color.status_paused
        }
        statusText.setText(statusLabel)
        val statusColor = ContextCompat.getColor(requireContext(), statusColorRes)
        statusText.setTextColor(statusColor)
        statusDot.backgroundTintList = ColorStateList.valueOf(statusColor)

        todayDurationText.text = RideStatsFormat.duration(dayLiveState?.todayDurationS ?: 0L)
        todayDistanceText.text = RideStatsFormat.distance(dayLiveState?.todayDistanceM ?: 0.0)
    }

    /** Three sub-states: between days, an actively-tracked open day, and an open day this service
     * instance never started itself (see [MultiDayRideTrackingService]'s resume-adoption kdoc) —
     * that last one only offers Resume, since neither Finish Day nor End Trip can produce a
     * trustworthy total for a day this instance has no live numbers for. */
    private fun renderActions(openDay: RideDay?, dayLiveState: MultiDayRideUiState?, latestDay: RideDay?) {
        when {
            openDay == null -> {
                dayOpenActionsRow.visibility = View.GONE
                betweenDaysActionsRow.visibility = View.VISIBLE
                endTripButton.visibility = View.VISIBLE
                val nextDisplayIndex = (latestDay?.dayIndex ?: -1) + 2
                startNextDayButton.text = getString(R.string.action_start_day_format, nextDisplayIndex)
            }
            dayLiveState != null -> {
                dayOpenActionsRow.visibility = View.VISIBLE
                betweenDaysActionsRow.visibility = View.GONE
                endTripButton.visibility = View.VISIBLE
                finishDayButton.visibility = View.VISIBLE
                pauseResumeButton.isEnabled = true
                if (dayLiveState.dayStatus == RideStatus.PAUSED) {
                    pauseResumeButton.setText(R.string.action_resume)
                    pauseResumeButton.setIconResource(R.drawable.ic_play)
                } else {
                    pauseResumeButton.setText(R.string.action_pause)
                    pauseResumeButton.setIconResource(R.drawable.ic_pause)
                }
            }
            else -> {
                // Day is open in the database, but no live service session matches it.
                dayOpenActionsRow.visibility = View.VISIBLE
                betweenDaysActionsRow.visibility = View.GONE
                endTripButton.visibility = View.GONE
                finishDayButton.visibility = View.GONE
                pauseResumeButton.isEnabled = true
                pauseResumeButton.setText(R.string.action_resume)
                pauseResumeButton.setIconResource(R.drawable.ic_play)
            }
        }
    }

    private fun onStartTripClicked() {
        if (pendingPermissionAction != null || MultiDayRideTrackingState.state.value.isBusy) return
        withLocationPermission { MultiDayRideTrackingService.startTrip(requireContext()) }
    }

    private fun onStartNextDayClicked() {
        val trip = activeTrip ?: return
        if (pendingPermissionAction != null || MultiDayRideTrackingState.state.value.isBusy) return
        withLocationPermission { MultiDayRideTrackingService.startNextDay(requireContext(), trip.id) }
    }

    private fun onPauseResumeClicked() {
        val live = MultiDayRideTrackingState.state.value
        if (live.isBusy) return
        val trip = activeTrip
        val day = openDay
        when {
            live.dayStatus == RideStatus.TRACKING -> MultiDayRideTrackingService.pause(requireContext())
            live.dayStatus == RideStatus.PAUSED -> MultiDayRideTrackingService.resume(requireContext())
            trip != null && day != null -> {
                // Adopting an open day this service instance never started — see class kdoc.
                withLocationPermission {
                    MultiDayRideTrackingService.resume(requireContext(), trip.id, day.id, day.dayIndex)
                }
            }
        }
    }

    private fun onLogRestDayClicked() {
        val trip = activeTrip ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.log_rest_day_confirm_title)
            .setMessage(R.string.log_rest_day_confirm_message)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val days = repository.getRideDays(trip.id)
                    val placeName = days.maxByOrNull { it.dayIndex }?.endPlaceName
                    repository.addNotTravelDay(trip.id, System.currentTimeMillis(), placeName)
                    loadState(MultiDayRideTrackingState.state.value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onEndTripClicked() {
        val trip = activeTrip ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.end_trip_confirm_title)
            .setMessage(R.string.end_trip_confirm_message)
            .setPositiveButton(R.string.action_end_trip) { _, _ ->
                if (openDay != null) {
                    MultiDayRideTrackingService.endTrip(requireContext())
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.completeTripWithNoOpenDay(trip.id, System.currentTimeMillis())
                        loadState(MultiDayRideTrackingState.state.value)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun withLocationPermission(action: () -> Unit) {
        val context = requireContext()
        val permissionsNeeded = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
