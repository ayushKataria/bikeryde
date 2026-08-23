package com.ayushkataria.bikeryde.ui.ride

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.RideStatus
import com.ayushkataria.bikeryde.ride.RideTrackingService
import com.ayushkataria.bikeryde.ride.RideTrackingState
import com.ayushkataria.bikeryde.ride.RideUiState
import com.ayushkataria.bikeryde.ui.placeholder.ComingSoonFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

class SingleDayRideFragment : Fragment(R.layout.fragment_single_day_ride) {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var durationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var activeActionsRow: android.widget.LinearLayout
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var postRideActionsRow: android.widget.LinearLayout
    private lateinit var createImageButton: MaterialButton
    private lateinit var createAnimationButton: MaterialButton

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
    }

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
