package com.ayushkataria.bikeryde

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ayushkataria.bikeryde.ride.RideStatus
import com.ayushkataria.bikeryde.ride.RideTrackingService
import com.ayushkataria.bikeryde.ride.RideTrackingState
import com.ayushkataria.bikeryde.ride.RideUiState
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: android.view.View
    private lateinit var statusText: android.widget.TextView
    private lateinit var durationText: android.widget.TextView
    private lateinit var distanceText: android.widget.TextView
    private lateinit var startButton: MaterialButton
    private lateinit var activeActionsRow: android.widget.LinearLayout
    private lateinit var pauseResumeButton: MaterialButton
    private lateinit var endButton: MaterialButton

    private var pendingStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted && pendingStart) {
            pendingStart = false
            RideTrackingService.start(this)
        } else if (!locationGranted) {
            pendingStart = false
            android.widget.Toast.makeText(this, R.string.permission_required, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        durationText = findViewById(R.id.durationText)
        distanceText = findViewById(R.id.distanceText)
        startButton = findViewById(R.id.startButton)
        activeActionsRow = findViewById(R.id.activeActionsRow)
        pauseResumeButton = findViewById(R.id.pauseResumeButton)
        endButton = findViewById(R.id.endButton)

        startButton.setOnClickListener { onStartClicked() }
        pauseResumeButton.setOnClickListener { onPauseResumeClicked() }
        endButton.setOnClickListener { onEndClicked() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideTrackingState.state.collect { render(it) }
            }
        }
    }

    private fun onStartClicked() {
        val permissionsNeeded = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            RideTrackingService.start(this)
        } else {
            pendingStart = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPauseResumeClicked() {
        when (RideTrackingState.state.value.status) {
            RideStatus.TRACKING -> RideTrackingService.pause(this)
            RideStatus.PAUSED -> RideTrackingService.resume(this)
            else -> Unit
        }
    }

    private fun onEndClicked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.end_ride_confirm_title)
            .setMessage(R.string.end_ride_confirm_message)
            .setPositiveButton(R.string.action_end) { _, _ -> RideTrackingService.end(this) }
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
        val statusColor = ContextCompat.getColor(this, statusColorRes)
        statusText.setTextColor(statusColor)
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(statusColor)

        val isActive = state.status == RideStatus.TRACKING || state.status == RideStatus.PAUSED
        startButton.visibility = if (isActive) android.view.View.GONE else android.view.View.VISIBLE
        activeActionsRow.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
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
}
