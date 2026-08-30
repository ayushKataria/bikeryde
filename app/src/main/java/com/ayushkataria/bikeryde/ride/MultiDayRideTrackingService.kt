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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Foreground service that owns GPS tracking for one *day* of a multi-day trip (design doc §5.2).
 * Mirrors [RideTrackingService]'s mechanics closely — same GPS/ticker/notification/busy-flag
 * pattern — but a day boundary ([ACTION_FINISH_DAY]) stops this service entirely without ending
 * the trip, and [ACTION_START_NEXT_DAY] spins up a fresh instance for the next day, which may be
 * the next morning. Because of that, this service never assumes it holds a running total spanning
 * the whole trip — only "today" — trip-wide totals live in the database, not in memory here.
 */
class MultiDayRideTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: RideRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var rideId: Long? = null
    private var rideDayId: Long? = null
    private var dayIndex: Int = 0
    private var dayStartTimeMs: Long? = null
    private var distanceM = 0.0
    private var accumulatedDurationS = 0L
    private var segmentStartElapsedRealtime = 0L
    private var isTracking = false
    private var lastLocation: Location? = null
    private var tickerJob: Job? = null
    private var isBusy = false
    private val stillRidingWatchdog = StillRidingWatchdog()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            stillRidingWatchdog.recordMovement()
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
            ACTION_START_TRIP -> onStartTrip()
            ACTION_START_NEXT_DAY -> {
                val id = intent.getLongExtra(EXTRA_RIDE_ID, -1L).takeIf { it != -1L }
                if (id != null) onStartNextDay(id)
            }
            ACTION_PAUSE -> onPause()
            ACTION_RESUME -> onResume(
                extraRideId = intent.getLongExtra(EXTRA_RIDE_ID, -1L).takeIf { it != -1L },
                extraRideDayId = intent.getLongExtra(EXTRA_RIDE_DAY_ID, -1L).takeIf { it != -1L },
                extraDayIndex = intent.getIntExtra(EXTRA_DAY_INDEX, 0)
            )
            ACTION_FINISH_DAY -> onFinishDay()
            ACTION_END_TRIP -> onEndTrip()
        }
        return START_STICKY
    }

    private fun onStartTrip() {
        if (rideId != null || isBusy) return
        setBusy(true)
        beginDaySession()
        scope.launch {
            val startTimeMs = System.currentTimeMillis()
            val location = lastLocation ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            val session = repository.startTrip(startTimeMs, location?.latitude, location?.longitude, placeName)
            rideId = session.rideId
            rideDayId = session.rideDayId
            dayIndex = 0
            dayStartTimeMs = startTimeMs
            isBusy = false
            publishState()
        }
    }

    private fun onStartNextDay(existingRideId: Long) {
        if (rideId != null || isBusy) return
        setBusy(true)
        rideId = existingRideId
        beginDaySession()
        scope.launch {
            val startTimeMs = System.currentTimeMillis()
            val location = lastLocation ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            val session = repository.startNextDay(existingRideId, startTimeMs, location?.latitude, location?.longitude, placeName)
            rideDayId = session.rideDayId
            dayIndex = session.dayIndex
            dayStartTimeMs = startTimeMs
            isBusy = false
            publishState()
        }
    }

    /** The GPS/ticker/notification setup shared by starting a trip's first day and starting any later day. */
    private fun beginDaySession() {
        startForegroundWithNotification(getString(R.string.notif_ride_in_progress))
        distanceM = 0.0
        accumulatedDurationS = 0L
        lastLocation = null
        isTracking = true
        segmentStartElapsedRealtime = SystemClock.elapsedRealtime()
        stillRidingWatchdog.recordMovement()
        startLocationUpdates()
        startTicker()
    }

    private fun onPause() {
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        if (!isTracking || isBusy) return
        setBusy(true)
        stopLocationUpdates()
        accumulatedDurationS += elapsedSegmentSeconds()
        isTracking = false
        updateNotification(getString(R.string.notif_ride_paused))
        scope.launch {
            val location = lastLocation ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            repository.pauseRide(id, dayId, System.currentTimeMillis(), location?.latitude, location?.longitude, placeName)
            isBusy = false
            publishState()
        }
    }

    /**
     * Resumes a paused day. [extraRideId]/[extraRideDayId]/[extraDayIndex] let a *freshly created*
     * instance of this service adopt an already-open day it never started itself — the day was
     * left open by an instance that's since been destroyed (the OS reclaiming a long-running
     * background service, or a crash) while the rider was still mid-day. Without this, tapping
     * Resume in that situation would silently do nothing, since a new instance otherwise has no
     * idea which ride/day it's resuming. Today's distance/duration restart from zero in that
     * recovery case — the same known limitation the single-day flow already has for a mid-ride
     * process death — rather than attempting to reconstruct it from partial GPS history.
     */
    private fun onResume(extraRideId: Long?, extraRideDayId: Long?, extraDayIndex: Int) {
        if (isBusy || isTracking) return
        if (rideId == null) {
            rideId = extraRideId ?: return
            rideDayId = extraRideDayId ?: return
            dayIndex = extraDayIndex
        }
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        setBusy(true)
        val locationAtResume = lastLocation
        lastLocation = null
        isTracking = true
        segmentStartElapsedRealtime = SystemClock.elapsedRealtime()
        stillRidingWatchdog.recordMovement()
        // Always (re-)establishes the foreground notification — a no-op if this instance was
        // already foreground (the normal same-instance pause->resume case), but required if this
        // instance just adopted an open day above and has never called startForeground() yet.
        startForegroundWithNotification(getString(R.string.notif_ride_in_progress))
        startLocationUpdates()
        startTicker()
        scope.launch {
            val location = locationAtResume ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            repository.resumeRide(id, dayId, System.currentTimeMillis(), location?.latitude, location?.longitude, placeName)
            isBusy = false
            publishState()
        }
    }

    /** Closes out today without ending the trip — the rider will tap "Start Day N+1" later, maybe
     * tomorrow, which starts a brand-new instance of this service. */
    private fun onFinishDay() {
        val dayId = rideDayId ?: return
        if (isBusy) return
        setBusy(true)
        closeOutDay { location, placeName, finalDistance, finalDuration, endTimeMs ->
            repository.finishDay(dayId, endTimeMs, location?.latitude, location?.longitude, placeName, finalDistance, finalDuration)
            MultiDayRideTrackingState.update(MultiDayRideUiState(isBusy = false))
            resetSessionState()
            stopForegroundCompat()
            stopSelf()
        }
    }

    /** Ends the whole trip from within an active day — finalizes today, then the trip itself,
     * with totals summed fresh from every [RideDay] rather than trusted from memory (see class kdoc). */
    private fun onEndTrip() {
        val id = rideId ?: return
        val dayId = rideDayId ?: return
        if (isBusy) return
        setBusy(true)
        closeOutDay { location, placeName, finalDistance, finalDuration, endTimeMs ->
            repository.endTrip(id, dayId, endTimeMs, location?.latitude, location?.longitude, placeName, finalDistance, finalDuration)
            MultiDayRideTrackingState.update(MultiDayRideUiState(rideId = id, dayStatus = RideStatus.COMPLETED))
            resetSessionState()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun closeOutDay(onFinalized: suspend (Location?, String?, Double, Long, Long) -> Unit) {
        if (isTracking) accumulatedDurationS += elapsedSegmentSeconds()
        isTracking = false
        stopLocationUpdates()
        tickerJob?.cancel()
        val finalDistance = distanceM
        val finalDuration = accumulatedDurationS
        val endTimeMs = System.currentTimeMillis()
        val locationAtEnd = lastLocation
        scope.launch {
            val location = locationAtEnd ?: getLastKnownLocation()
            val placeName = reverseGeocode(location)
            onFinalized(location, placeName, finalDistance, finalDuration, endTimeMs)
        }
    }

    private fun resetSessionState() {
        isBusy = false
        rideId = null
        rideDayId = null
        dayStartTimeMs = null
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        MultiDayRideTrackingState.update(MultiDayRideTrackingState.state.value.copy(isBusy = busy))
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
                val addresses = Geocoder(this@MultiDayRideTrackingService, Locale.getDefault())
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
                if (isTracking && stillRidingWatchdog.checkStillness()) {
                    StillRidingNotifier.show(this@MultiDayRideTrackingService, CHANNEL_ID)
                }
                delay(1000.milliseconds)
            }
        }
    }

    private fun publishState() {
        val id = rideId ?: return
        val liveDuration = accumulatedDurationS + if (isTracking) elapsedSegmentSeconds() else 0L
        val liveTotalTime = dayStartTimeMs?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
        MultiDayRideTrackingState.update(
            MultiDayRideUiState(
                rideId = id,
                rideDayId = rideDayId,
                dayIndex = dayIndex,
                dayStatus = if (isTracking) RideStatus.TRACKING else RideStatus.PAUSED,
                todayDistanceM = distanceM,
                todayDurationS = liveDuration,
                todayTotalTimeS = liveTotalTime,
                isBusy = isBusy
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
        private const val NOTIFICATION_ID = 1003
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val MIN_UPDATE_DISTANCE_M = 4f

        const val ACTION_START_TRIP = "com.ayushkataria.bikeryde.ride.action.START_TRIP"
        const val ACTION_START_NEXT_DAY = "com.ayushkataria.bikeryde.ride.action.START_NEXT_DAY"
        const val ACTION_PAUSE = "com.ayushkataria.bikeryde.ride.action.MULTI_PAUSE"
        const val ACTION_RESUME = "com.ayushkataria.bikeryde.ride.action.MULTI_RESUME"
        const val ACTION_FINISH_DAY = "com.ayushkataria.bikeryde.ride.action.FINISH_DAY"
        const val ACTION_END_TRIP = "com.ayushkataria.bikeryde.ride.action.END_TRIP"
        private const val EXTRA_RIDE_ID = "rideId"
        private const val EXTRA_RIDE_DAY_ID = "rideDayId"
        private const val EXTRA_DAY_INDEX = "dayIndex"

        private fun intent(context: Context, action: String) =
            Intent(context, MultiDayRideTrackingService::class.java).setAction(action)

        fun startTrip(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_START_TRIP))

        fun startNextDay(context: Context, rideId: Long) = ContextCompat.startForegroundService(
            context,
            intent(context, ACTION_START_NEXT_DAY).putExtra(EXTRA_RIDE_ID, rideId)
        )

        fun pause(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_PAUSE))

        /** [rideId]/[rideDayId]/[dayIndex] are only needed to recover an open day the tracking
         * service instance itself never started (see [onResume]'s kdoc) — omit them for the normal
         * same-session pause->resume tap. */
        fun resume(context: Context, rideId: Long? = null, rideDayId: Long? = null, dayIndex: Int = 0) {
            val request = intent(context, ACTION_RESUME)
            if (rideId != null) request.putExtra(EXTRA_RIDE_ID, rideId)
            if (rideDayId != null) request.putExtra(EXTRA_RIDE_DAY_ID, rideDayId)
            request.putExtra(EXTRA_DAY_INDEX, dayIndex)
            ContextCompat.startForegroundService(context, request)
        }

        fun finishDay(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_FINISH_DAY))
        fun endTrip(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_END_TRIP))
    }
}
