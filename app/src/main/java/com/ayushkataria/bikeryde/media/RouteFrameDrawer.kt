package com.ayushkataria.bikeryde.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RidePoint
import com.ayushkataria.bikeryde.ride.RouteProjection
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Draws one frame of a ride's route + stats overlay onto a [Canvas] of any size. Used both for the
 * static image (a single call at `progress = 1f`) and every frame of the animated video (called
 * once per frame with `progress` sweeping 0..1) — so a static image is just a one-frame video.
 *
 * Takes a [RideRenderData] rather than a single day's points, so stitching a multi-day ride's
 * route end to end (once multi-day tracking exists) needs no change here — [RideRenderData.days]
 * already holds however many day segments the ride has, and their points/stops are concatenated
 * in order.
 */
object RouteFrameDrawer {

    private val backgroundColor = Color.parseColor("#FFF8F5")
    private val cardColor = Color.parseColor("#F6E4DB")
    private val routeColor = Color.parseColor("#E8622B")
    private val darkTextColor = Color.parseColor("#221A15")
    private val darkLabelColor = Color.parseColor("#53443B")
    private val lightTextColor = Color.WHITE
    private val lightLabelColor = Color.parseColor("#EAEAEA")
    private val scrimColor = Color.parseColor("#73000000")
    private val startColor = Color.parseColor("#39662E")
    private val pauseResumeColor = Color.parseColor("#8A5A00")
    private val endColor = Color.parseColor("#BA1A1A")

    fun draw(canvas: Canvas, width: Int, height: Int, data: RideRenderData, progress: Float) {
        val usesPhoto = data.coverImagePath != null || data.allStops.any { it.backgroundImagePath != null }
        val textColor = if (usesPhoto) lightTextColor else darkTextColor
        val labelColor = if (usesPhoto) lightLabelColor else darkLabelColor

        canvas.drawColor(if (usesPhoto) Color.BLACK else backgroundColor)
        if (usesPhoto) drawPhotoBackground(canvas, width, height, data, progress)

        val margin = width * 0.06f
        val headerBottom = height * 0.1f
        val footerTop = height * 0.78f

        drawHeader(canvas, width, margin, headerBottom, data.title, textColor)

        val allPoints = data.allPoints
        val routeBounds = RectF(margin, headerBottom + margin * 0.5f, width - margin, footerTop - margin * 0.5f)
        // A background photo is the whole point of adding one — don't cover most of it with an
        // opaque card. The route/markers/labels draw with enough contrast (scrim + white text +
        // label pills) to read directly over the photo instead.
        if (!usesPhoto) drawRouteCard(canvas, routeBounds)

        if (allPoints.size < 2) {
            drawEmptyRouteMessage(canvas, routeBounds, labelColor)
            drawStats(canvas, width, height, footerTop, data, fraction = 1f, textColor, labelColor)
            return
        }

        val inset = RectF(
            routeBounds.left + routeBounds.width() * 0.08f,
            routeBounds.top + routeBounds.height() * 0.08f,
            routeBounds.right - routeBounds.width() * 0.08f,
            routeBounds.bottom - routeBounds.height() * 0.08f
        )
        val viewport = RouteProjection.viewportFor(allPoints, inset)
        val visibleCount = min(allPoints.size, ceil(progress * allPoints.size).toInt().coerceAtLeast(2))

        drawRoute(canvas, routeBounds, viewport, allPoints, visibleCount)
        drawStops(canvas, routeBounds, viewport, allPoints, data, visibleCount)

        drawStats(canvas, width, height, footerTop, data, visibleFraction(allPoints.size, visibleCount), textColor, labelColor)
    }

    /** How far along the animated route (in GPS-point units) a given stop's marker/photo appears —
     * stops are recorded in chronological order, same as the GPS trail, so a stop's ordinal
     * position among all stops approximates its position along the route. */
    private fun stopRevealPointIndex(stopIndex: Int, totalStops: Int, totalPoints: Int): Float =
        (stopIndex.toFloat() / (totalStops - 1).coerceAtLeast(1)) * (totalPoints - 1)

    /**
     * Draws the active background photo: the single [RideRenderData.coverImagePath] for a static
     * image, or — for a video — crossfades between each stop's [RenderStop.backgroundImagePath] as
     * the route animates past it, with a dark scrim under both so the route/stats stay legible.
     */
    private fun drawPhotoBackground(canvas: Canvas, width: Int, height: Int, data: RideRenderData, progress: Float) {
        val dest = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val coverPath = data.coverImagePath
        if (coverPath != null) {
            BackgroundImageCache.get(coverPath)?.let { bitmap ->
                BackgroundImageCache.drawCenterCrop(canvas, bitmap, dest, paint)
            }
            drawScrim(canvas, width, height)
            return
        }

        val stops = data.allStops
        val totalPoints = data.allPoints.size
        if (stops.isEmpty() || totalPoints < 2) return
        val imagedStops = stops.mapIndexedNotNull { index, stop ->
            stop.backgroundImagePath?.let { path -> stopRevealPointIndex(index, stops.size, totalPoints) to path }
        }
        if (imagedStops.isEmpty()) return

        val playhead = progress * (totalPoints - 1).coerceAtLeast(1)
        val crossfadeWindow = (totalPoints * 0.08f).coerceAtLeast(3f)
        val prev = imagedStops.lastOrNull { it.first <= playhead }
        val next = imagedStops.firstOrNull { it.first > playhead }

        if (prev != null) {
            BackgroundImageCache.get(prev.second)?.let { bitmap ->
                paint.alpha = 255
                BackgroundImageCache.drawCenterCrop(canvas, bitmap, dest, paint)
            }
        }
        val fadeTarget = next
        if (fadeTarget != null) {
            val fadeSpan = if (prev != null) (fadeTarget.first - prev.first) else crossfadeWindow
            val fadeStart = fadeTarget.first - min(crossfadeWindow, fadeSpan)
            val alpha = ((playhead - fadeStart) / (fadeTarget.first - fadeStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
            if (alpha > 0f) {
                BackgroundImageCache.get(fadeTarget.second)?.let { bitmap ->
                    paint.alpha = (alpha * 255).toInt()
                    BackgroundImageCache.drawCenterCrop(canvas, bitmap, dest, paint)
                }
            }
        }
        drawScrim(canvas, width, height)
    }

    private fun drawScrim(canvas: Canvas, width: Int, height: Int) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = scrimColor })
    }

    private fun drawHeader(canvas: Canvas, width: Int, margin: Float, headerBottom: Float, title: String, textColor: Int) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }
        val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeColor
            textSize = width * 0.032f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(title, margin, headerBottom * 0.65f, titlePaint)
        canvas.drawText("BikeRyde", width - margin, headerBottom * 0.65f, wordmarkPaint)
    }

    private fun drawRouteCard(canvas: Canvas, bounds: RectF) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        canvas.drawRoundRect(bounds, bounds.width() * 0.04f, bounds.width() * 0.04f, cardPaint)
    }

    private fun drawEmptyRouteMessage(canvas: Canvas, bounds: RectF, textColor: Int) {
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = bounds.width() * 0.045f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No route data", bounds.centerX(), bounds.centerY(), emptyPaint)
    }

    /** Draws the route polyline up to [visibleCount] points. */
    private fun drawRoute(
        canvas: Canvas,
        bounds: RectF,
        viewport: RouteProjection.Viewport,
        allPoints: List<RidePoint>,
        visibleCount: Int
    ) {
        val projected = allPoints.subList(0, visibleCount).map { viewport.project(it) }

        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = bounds.width() * 0.012f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = routeColor
        }
        canvas.drawPath(RouteProjection.smoothedPath(projected), routePaint)
    }

    private fun drawStops(
        canvas: Canvas,
        bounds: RectF,
        viewport: RouteProjection.Viewport,
        allPoints: List<RidePoint>,
        data: RideRenderData,
        visibleCount: Int
    ) {
        if (visibleCount < 1) return
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = bounds.width() * 0.006f
            color = Color.WHITE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkTextColor
            textSize = bounds.width() * 0.028f
            textAlign = Paint.Align.CENTER
        }
        val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            alpha = 235
        }
        val markerRadius = bounds.width() * 0.018f

        val stops = data.allStops
        stops.forEachIndexed { index, stop ->
            val lat = stop.lat
            val lng = stop.lng
            if (lat == null || lng == null) return@forEachIndexed
            val revealAtIndex = stopRevealPointIndex(index, stops.size, allPoints.size)
            if (revealAtIndex > visibleCount - 1) return@forEachIndexed

            val point = viewport.project(RidePoint(lat, lng, null))
            markerPaint.color = when (stop.action) {
                RideEventAction.START -> startColor
                RideEventAction.END -> endColor
                RideEventAction.PAUSE, RideEventAction.RESUME -> pauseResumeColor
            }
            canvas.drawCircle(point.x, point.y, markerRadius, markerPaint)
            canvas.drawCircle(point.x, point.y, markerRadius, markerStrokePaint)
            stop.displayName?.let { name ->
                val labelY = point.y - markerRadius * 1.8f
                val textWidth = labelPaint.measureText(name)
                val pad = markerRadius * 0.5f
                canvas.drawRoundRect(
                    point.x - textWidth / 2f - pad,
                    labelY + labelPaint.ascent() - pad,
                    point.x + textWidth / 2f + pad,
                    labelY + labelPaint.descent() + pad,
                    pad,
                    pad,
                    labelBackgroundPaint
                )
                canvas.drawText(name, point.x, labelY, labelPaint)
            }
        }
    }

    private fun visibleFraction(totalPoints: Int, visibleCount: Int): Float =
        if (totalPoints < 2) 1f else visibleCount.toFloat() / totalPoints

    private fun drawStats(
        canvas: Canvas,
        width: Int,
        height: Int,
        footerTop: Float,
        data: RideRenderData,
        fraction: Float,
        textColor: Int,
        labelColor: Int
    ) {
        val allPoints = data.allPoints
        val runningDistanceM =
            if (allPoints.size < 2) data.totalDistanceM
            else cumulativeDistanceM(allPoints.subList(0, min(allPoints.size, ceil(fraction * allPoints.size).toInt().coerceAtLeast(2))))
        val runningDurationS = (data.totalDurationS * fraction).toLong()

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = width * 0.03f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }

        val columnWidth = (width - width * 0.12f) / 2f
        val col1 = width * 0.06f
        val col2 = col1 + columnWidth

        // The stats block is vertically centered within the footer band (with room to breathe on
        // both sides), rather than packed against its top with the bottom row crowding the edge.
        val footerHeight = height - footerTop
        val topRowLabelY = footerTop + footerHeight * 0.22f
        val topRowValueY = footerTop + footerHeight * 0.38f
        val bottomRowLabelY = footerTop + footerHeight * 0.64f
        val bottomRowValueY = footerTop + footerHeight * 0.80f

        canvas.drawText("DISTANCE", col1, topRowLabelY, labelPaint)
        canvas.drawText(String.format(Locale.US, "%.2f km", runningDistanceM / 1000.0), col1, topRowValueY, valuePaint)

        canvas.drawText("TIME ON ROAD", col2, topRowLabelY, labelPaint)
        canvas.drawText(formatDuration(runningDurationS), col2, topRowValueY, valuePaint)

        canvas.drawText("AVG SPEED", col1, bottomRowLabelY, labelPaint)
        canvas.drawText(String.format(Locale.US, "%.1f km/h", data.avgSpeedKmh), col1, bottomRowValueY, valuePaint)

        canvas.drawText("MAX SPEED", col2, bottomRowLabelY, labelPaint)
        val maxSpeedText = data.maxSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "–"
        canvas.drawText(maxSpeedText, col2, bottomRowValueY, valuePaint)
    }

    private fun cumulativeDistanceM(points: List<RidePoint>): Double {
        var total = 0.0
        val results = FloatArray(1)
        for (i in 0 until points.size - 1) {
            Location.distanceBetween(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng, results)
            total += results[0]
        }
        return total
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }
}
