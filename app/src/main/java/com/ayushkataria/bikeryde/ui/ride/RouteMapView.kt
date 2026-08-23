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
import kotlin.math.cos
import kotlin.math.max

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

        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLng = points[0].lng
        var maxLng = points[0].lng
        for (p in points) {
            minLat = minOf(minLat, p.lat)
            maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng)
            maxLng = maxOf(maxLng, p.lng)
        }

        // Longitude degrees are narrower than latitude ones away from the equator — scale by
        // cos(latitude) so a square-ish route doesn't come out visually stretched.
        val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        val lngScale = max(cos(midLatRad), 0.15)

        val latSpan = max(maxLat - minLat, 1e-6)
        val lngSpan = max((maxLng - minLng) * lngScale, 1e-6)
        val span = max(latSpan, lngSpan)

        fun project(p: RidePoint): Pair<Float, Float> {
            val x = bounds.left + ((p.lng - minLng) * lngScale / span).toFloat() * bounds.width()
            // Screen y grows downward; latitude grows northward, so flip.
            val y = bounds.bottom - ((p.lat - minLat) / span).toFloat() * bounds.height()
            return x to y
        }

        val projected = points.map { project(it) }
        canvas.drawPath(smoothedPath(projected), routePaint)

        events.forEach { event ->
            val lat = event.lat
            val lng = event.lng
            if (lat == null || lng == null) return@forEach
            val (x, y) = project(RidePoint(lat, lng, null))
            markerPaint.color = markerColorFor(event.action)
            canvas.drawCircle(x, y, MARKER_RADIUS, markerPaint)
            canvas.drawCircle(x, y, MARKER_RADIUS, markerStrokePaint)
        }
    }

    /**
     * Turns the raw, sometimes-sparse GPS fixes into a smooth curve (Catmull-Rom spline,
     * expressed as cubic Beziers) instead of a jagged point-to-point line — the shape a hand
     * would draw tracing the same points, closer to the road actually ridden.
     */
    private fun smoothedPath(pts: List<Pair<Float, Float>>): android.graphics.Path {
        val path = android.graphics.Path()
        path.moveTo(pts[0].first, pts[0].second)
        if (pts.size == 2) {
            path.lineTo(pts[1].first, pts[1].second)
            return path
        }
        for (i in 0 until pts.size - 1) {
            val p0 = pts[if (i == 0) i else i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[if (i + 2 < pts.size) i + 2 else i + 1]

            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f

            path.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
        }
        return path
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
