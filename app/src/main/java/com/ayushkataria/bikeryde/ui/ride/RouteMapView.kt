package com.ayushkataria.bikeryde.ui.ride

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ayushkataria.bikeryde.ride.RideEvent
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RidePoint
import com.ayushkataria.bikeryde.ride.RouteProjection

/**
 * Draws a recorded ride's GPS trail as a simple flattened line plot, with colored dots for the
 * start/pause/resume/end stops. Deliberately not backed by any Maps SDK — projecting the raw
 * lat/lng points is enough for a personal ride's route shape and needs no API key.
 */
class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<RidePoint> = emptyList()
    private var events: List<RideEvent> = emptyList()

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }

    private var startColor: Int = Color.parseColor("#39662E")
    private var pauseResumeColor: Int = Color.parseColor("#8A5A00")
    private var endColor: Int = Color.parseColor("#BA1A1A")

    fun setRouteColor(color: Int) {
        routePaint.color = color
    }

    fun setEmptyTextColor(color: Int) {
        emptyPaint.color = color
    }

    fun setMarkerColors(start: Int, pauseResume: Int, end: Int) {
        startColor = start
        pauseResumeColor = pauseResume
        endColor = end
    }

    fun submit(points: List<RidePoint>, events: List<RideEvent>) {
        this.points = points
        this.events = events
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) {
            canvas.drawText(emptyMessage(), width / 2f, height / 2f, emptyPaint)
            return
        }

        val padding = 32f
        val bounds = RectF(padding, padding, width - padding, height - padding)
        val viewport = RouteProjection.viewportFor(points, bounds)

        val projected = points.map { viewport.project(it) }
        canvas.drawPath(RouteProjection.smoothedPath(projected), routePaint)

        events.forEach { event ->
            val lat = event.lat
            val lng = event.lng
            if (lat == null || lng == null) return@forEach
            val point = viewport.project(RidePoint(lat, lng, null))
            markerPaint.color = markerColorFor(event.action)
            canvas.drawCircle(point.x, point.y, MARKER_RADIUS, markerPaint)
            canvas.drawCircle(point.x, point.y, MARKER_RADIUS, markerStrokePaint)
        }
    }

    private fun emptyMessage(): String = if (points.isEmpty()) NO_DATA_TEXT else NEEDS_MORE_POINTS_TEXT

    private fun markerColorFor(action: RideEventAction): Int = when (action) {
        RideEventAction.START -> startColor
        RideEventAction.END -> endColor
        RideEventAction.PAUSE, RideEventAction.RESUME -> pauseResumeColor
    }

    companion object {
        private const val MARKER_RADIUS = 14f
        private const val NO_DATA_TEXT = "No GPS data recorded yet"
        private const val NEEDS_MORE_POINTS_TEXT = "Recording route…"
    }
}
