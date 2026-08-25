package com.ayushkataria.bikeryde.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ayushkataria.bikeryde.MainActivity
import com.ayushkataria.bikeryde.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Foreground service that owns GPS tracking for a single-day ride. All start/pause/resume/end
 * control comes in as an [Intent] action from the UI; live stats are published to
 * [RideTrackingState] so the UI never has to bind to this service directly.
 */
class RideTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: RideRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var rideId: Long? = null
    private var rideDayId: Long? = null
    private var distanceM = 0.0
    private var accumulatedDurationS = 0L
    private var segmentStartElapsedRealtime = 0L
    private var isTracking = false
    private var lastLocation: Location? = null
    private var tickerJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation?.let { distanceM += it.distanceTo(location) }
            lastLocation = location
            val dayId = rideDayId ?: return
            scope.launch {
                repository.addGpsPoint(
                    rideDayId = dayId,
                    timestamp = location.time,
                    lat = location.latitude,
                    lng = location.longitude,
                    elevation = if (location.hasAltitude()) location.altitude else null,
                    speed = if (location.hasSpeed()) location.speed else null
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = RideRepository(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_PAUSE -> onPause()
            ACTION_RESUME -> onResume()
            ACTION_END -> onEnd()
        }
        return START_STICKY
    }

    private fun onStart() {
        if (rideId != null) return
        startForegroundWithNotification(statusText = getString(R.string.notif_ride_in_progress))
        distanceM = 0.0
        accumulatedDurationS = 0L
        lastLocation = null
        isTracking = true
        segmentStartElapsedRealtime = SystemClock.elapsedRealtime()
        scope.launch {
            val location = lastLocation ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            val session = repository.startRide(System.currentTimeMillis(), location?.latitude, location?.longitude, placeName)
            rideId = session.rideId
            rideDayId = session.rideDayId
            publishState()
        }
        startLocationUpdates()
        startTicker()
    }

    private fun onPause() {
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        if (!isTracking) return
        stopLocationUpdates()
        accumulatedDurationS += elapsedSegmentSeconds()
        isTracking = false
        tickerJob?.cancel()
        updateNotification(getString(R.string.notif_ride_paused))
        scope.launch {
            val location = lastLocation ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            repository.pauseRide(id, dayId, System.currentTimeMillis(), location?.latitude, location?.longitude, placeName)
            publishState()
        }
    }

    private fun onResume() {
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        if (isTracking) return
        val locationAtResume = lastLocation
        lastLocation = null
        isTracking = true
        segmentStartElapsedRealtime = SystemClock.elapsedRealtime()
        startLocationUpdates()
        startTicker()
        updateNotification(getString(R.string.notif_ride_in_progress))
        scope.launch {
            val location = locationAtResume ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            repository.resumeRide(id, dayId, System.currentTimeMillis(), location?.latitude, location?.longitude, placeName)
            publishState()
        }
    }

    private fun onEnd() {
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        if (isTracking) {
            accumulatedDurationS += elapsedSegmentSeconds()
        }
        isTracking = false
        stopLocationUpdates()
        tickerJob?.cancel()
        val finalDistance = distanceM
        val finalDuration = accumulatedDurationS
        val locationAtEnd = lastLocation
        scope.launch {
            val location = locationAtEnd ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            repository.endRide(
                rideId = id,
                rideDayId = dayId,
                timestamp = System.currentTimeMillis(),
                lat = location?.latitude,
                lng = location?.longitude,
                placeName = placeName,
                totalDistanceM = finalDistance,
                totalDurationS = finalDuration
            )
            RideTrackingState.update(
                RideUiState(
                    rideId = id,
                    status = RideStatus.COMPLETED,
                    distanceM = finalDistance,
                    durationS = finalDuration
                )
            )
            rideId = null
            rideDayId = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private suspend fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            }.onFailure { continuation.resume(null) }
        }
    }

    private suspend fun reverseGeocode(location: Location?): String? {
        if (location == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(this@RideTrackingService, Locale.getDefault())
                    .getFromLocation(location.latitude, location.longitude, 1)
                addresses?.firstOrNull()?.let { address ->
                    address.locality ?: address.subAdminArea ?: address.adminArea ?: address.featureName
                }
            }.getOrNull()
        }
    }

    private fun elapsedSegmentSeconds(): Long =
        (SystemClock.elapsedRealtime() - segmentStartElapsedRealtime) / 1000

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                publishState()
                delay(1000)
            }
        }
    }

    private fun publishState() {
        val id = rideId ?: return
        val liveDuration = accumulatedDurationS + if (isTracking) elapsedSegmentSeconds() else 0L
        RideTrackingState.update(
            RideUiState(
                rideId = id,
                status = if (isTracking) RideStatus.TRACKING else RideStatus.PAUSED,
                distanceM = distanceM,
                durationS = liveDuration
            )
        )
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_M)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    override fun onDestroy() {
        stopLocationUpdates()
        tickerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val MIN_UPDATE_DISTANCE_M = 4f

        const val ACTION_START = "com.ayushkataria.bikeryde.ride.action.START"
        const val ACTION_PAUSE = "com.ayushkataria.bikeryde.ride.action.PAUSE"
        const val ACTION_RESUME = "com.ayushkataria.bikeryde.ride.action.RESUME"
        const val ACTION_END = "com.ayushkataria.bikeryde.ride.action.END"

        private fun intent(context: Context, action: String) =
            Intent(context, RideTrackingService::class.java).setAction(action)

        fun start(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        fun pause(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_PAUSE))
        fun resume(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_RESUME))
        fun end(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_END))
    }
}
