package com.ayushkataria.bikeryde.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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
 * Draws a ride's route + stats overlay. [prepare] does all the *per-render* work once — geometry,
 * point projection, distance prefix sums, and every [Paint] — and returns a [PreparedRender] whose
 * [PreparedRender.draw] is called once per output frame (a static image is just one call at
 * `progress = 1f`; a video calls it once per frame, sweeping 0..1).
 *
 * This split exists because [PreparedRender.draw] used to redo all of that setup — including a full
 * scan of every GPS point for distance (via the relatively expensive [Location.distanceBetween])
 * and re-projecting every visible point — on *every single frame*. At a few hundred frames per
 * video, that repeated O(points) work was slow enough to blow well past the target render duration,
 * and since the encoder stamps each frame's timestamp from when it actually arrives (not a fixed
 * clock), slow frames don't just make the render take longer — they make the *video's own playback*
 * stutter, because frames physically arrive at uneven intervals. Precomputing once fixes both.
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

    fun prepare(width: Int, height: Int, data: RideRenderData): PreparedRender =
        PreparedRender(width, height, data)

    /** How far along the animated route (in GPS-point units) a given stop's marker/photo appears —
     * stops are recorded in chronological order, same as the GPS trail, so a stop's ordinal
     * position among all stops approximates its position along the route. */
    private fun stopRevealPointIndex(stopIndex: Int, totalStops: Int, totalPoints: Int): Float =
        (stopIndex.toFloat() / (totalStops - 1).coerceAtLeast(1)) * (totalPoints - 1)

    private class StopEntry(val revealIndex: Float, val point: PointF, val color: Int, val label: String?)

    class PreparedRender internal constructor(
        private val width: Int,
        private val height: Int,
        private val data: RideRenderData
    ) {
        private val usesPhoto = data.coverImagePath != null || data.allStops.any { it.backgroundImagePath != null }
        private val textColor = if (usesPhoto) lightTextColor else darkTextColor
        private val labelColor = if (usesPhoto) lightLabelColor else darkLabelColor

        private val margin = width * 0.06f
        private val headerBottom = height * 0.1f
        private val footerTop = height * 0.78f
        private val fullCanvasRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        private val routeBounds = RectF(margin, headerBottom + margin * 0.5f, width - margin, footerTop - margin * 0.5f)
        private val inset = RectF(
            routeBounds.left + routeBounds.width() * 0.08f,
            routeBounds.top + routeBounds.height() * 0.08f,
            routeBounds.right - routeBounds.width() * 0.08f,
            routeBounds.bottom - routeBounds.height() * 0.08f
        )

        private val allPoints = data.allPoints
        private val hasRoute = allPoints.size >= 2

        private val viewport = if (hasRoute) RouteProjection.viewportFor(allPoints, inset) else null
        private val projectedPoints: List<PointF> = viewport?.let { vp -> allPoints.map { vp.project(it) } } ?: emptyList()

        /** distancePrefixM[i] = cumulative distance from point 0 to point i — an O(1) lookup per
         * frame instead of re-walking every point (with a geodesic distance calc) each time. */
        private val distancePrefixM: DoubleArray = run {
            val prefix = DoubleArray(allPoints.size)
            val results = FloatArray(1)
            for (i in 1 until allPoints.size) {
                Location.distanceBetween(allPoints[i - 1].lat, allPoints[i - 1].lng, allPoints[i].lat, allPoints[i].lng, results)
                prefix[i] = prefix[i - 1] + results[0]
            }
            prefix
        }

        private val markerRadius = routeBounds.width() * 0.018f
        private val stopEntries: List<StopEntry> = if (hasRoute) {
            val stops = data.allStops
            val vp = viewport!!
            stops.mapIndexedNotNull { index, stop ->
                val lat = stop.lat
                val lng = stop.lng
                if (lat == null || lng == null) return@mapIndexedNotNull null
                StopEntry(
                    revealIndex = stopRevealPointIndex(index, stops.size, allPoints.size),
                    point = vp.project(RidePoint(lat, lng, null)),
                    color = when (stop.action) {
                        RideEventAction.START -> startColor
                        RideEventAction.END -> endColor
                        RideEventAction.PAUSE, RideEventAction.RESUME -> pauseResumeColor
                    },
                    label = stop.displayName
                )
            }
        } else emptyList()

        /** (revealIndex, photo path) for stops with a background photo — video crossfade only. */
        private val imagedStops: List<Pair<Float, String>> = if (hasRoute && data.coverImagePath == null) {
            val stops = data.allStops
            stops.mapIndexedNotNull { index, stop ->
                stop.backgroundImagePath?.let { path -> stopRevealPointIndex(index, stops.size, allPoints.size) to path }
            }
        } else emptyList()

        // Precomputed final stat strings that don't change frame to frame (only distance/time run).
        private val avgSpeedText = String.format(Locale.US, "%.1f km/h", data.avgSpeedKmh)
        private val maxSpeedText = data.maxSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "–"

        // All Paint objects are built once and reused every frame; only color/alpha mutate.
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeColor
            textSize = width * 0.032f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = routeBounds.width() * 0.045f
            textAlign = Paint.Align.CENTER
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.012f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = routeColor
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.006f
            color = Color.WHITE
        }
        private val stopLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkTextColor
            textSize = routeBounds.width() * 0.028f
            textAlign = Paint.Align.CENTER
        }
        private val stopLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            alpha = 235
        }
        private val statsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = width * 0.03f
        }
        private val statsValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val scrimPaint = Paint().apply { color = scrimColor }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(canvas: Canvas, progress: Float) {
            canvas.drawColor(if (usesPhoto) Color.BLACK else backgroundColor)
            if (usesPhoto) drawPhotoBackground(canvas, progress)

            canvas.drawText(data.title, margin, headerBottom * 0.65f, titlePaint)
            canvas.drawText("BikeRyde", width - margin, headerBottom * 0.65f, wordmarkPaint)

            // A background photo is the whole point of adding one — don't cover most of it with an
            // opaque card. The route/markers/labels draw with enough contrast (scrim + white text +
            // label pills) to read directly over the photo instead.
            if (!usesPhoto) {
                canvas.drawRoundRect(routeBounds, routeBounds.width() * 0.04f, routeBounds.width() * 0.04f, cardPaint)
            }

            if (!hasRoute) {
                canvas.drawText("No route data", routeBounds.centerX(), routeBounds.centerY(), emptyPaint)
                drawStats(canvas, fraction = 1f)
                return
            }

            val visibleCount = min(allPoints.size, ceil(progress * allPoints.size).toInt().coerceAtLeast(2))
            canvas.drawPath(RouteProjection.smoothedPath(projectedPoints.subList(0, visibleCount)), routePaint)
            drawStops(canvas, visibleCount)
            drawStats(canvas, visibleFraction(allPoints.size, visibleCount))
        }

        private fun drawStops(canvas: Canvas, visibleCount: Int) {
            for (entry in stopEntries) {
                if (entry.revealIndex > visibleCount - 1) continue
                markerPaint.color = entry.color
                canvas.drawCircle(entry.point.x, entry.point.y, markerRadius, markerPaint)
                canvas.drawCircle(entry.point.x, entry.point.y, markerRadius, markerStrokePaint)
                entry.label?.let { name ->
                    val labelY = entry.point.y - markerRadius * 1.8f
                    val textWidth = stopLabelPaint.measureText(name)
                    val pad = markerRadius * 0.5f
                    canvas.drawRoundRect(
                        entry.point.x - textWidth / 2f - pad,
                        labelY + stopLabelPaint.ascent() - pad,
                        entry.point.x + textWidth / 2f + pad,
                        labelY + stopLabelPaint.descent() + pad,
                        pad,
                        pad,
                        stopLabelBackgroundPaint
                    )
                    canvas.drawText(name, entry.point.x, labelY, stopLabelPaint)
                }
            }
        }

        /**
         * Draws the active background photo: the single [RideRenderData.coverImagePath] for a
         * static image, or — for a video — crossfades between each stop's photo as the route
         * animates past it, with a dark scrim under both so the route/stats stay legible.
         */
        private fun drawPhotoBackground(canvas: Canvas, progress: Float) {
            val coverPath = data.coverImagePath
            if (coverPath != null) {
                BackgroundImageCache.get(coverPath)?.let { bitmap ->
                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
                canvas.drawRect(fullCanvasRect, scrimPaint)
                return
            }

            if (imagedStops.isEmpty()) return
            val totalPoints = allPoints.size
            val playhead = progress * (totalPoints - 1).coerceAtLeast(1)
            val crossfadeWindow = (totalPoints * 0.08f).coerceAtLeast(3f)
            val prev = imagedStops.lastOrNull { it.first <= playhead }
            val next = imagedStops.firstOrNull { it.first > playhead }

            if (prev != null) {
                BackgroundImageCache.get(prev.second)?.let { bitmap ->
                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
            }
            if (next != null) {
                val fadeSpan = if (prev != null) (next.first - prev.first) else crossfadeWindow
                val fadeStart = next.first - min(crossfadeWindow, fadeSpan)
                val alpha = ((playhead - fadeStart) / (next.first - fadeStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
                if (alpha > 0f) {
                    BackgroundImageCache.get(next.second)?.let { bitmap ->
                        bitmapPaint.alpha = (alpha * 255).toInt()
                        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                    }
                }
            }
            canvas.drawRect(fullCanvasRect, scrimPaint)
        }

        private fun visibleFraction(totalPoints: Int, visibleCount: Int): Float =
            if (totalPoints < 2) 1f else visibleCount.toFloat() / totalPoints

        private fun drawStats(canvas: Canvas, fraction: Float) {
            val runningDistanceM = if (allPoints.size < 2) {
                data.totalDistanceM
            } else {
                val idx = (ceil(fraction * allPoints.size).toInt().coerceAtLeast(2) - 1).coerceIn(0, allPoints.size - 1)
                distancePrefixM[idx]
            }
            val runningDurationS = (data.totalDurationS * fraction).toLong()

            val columnWidth = (width - width * 0.12f) / 2f
            val col1 = width * 0.06f
            val col2 = col1 + columnWidth

            // The stats block is vertically centered within the footer band (with room to breathe
            // on both sides), rather than packed against its top with the bottom row crowding the edge.
            val footerHeight = height - footerTop
            val topRowLabelY = footerTop + footerHeight * 0.22f
            val topRowValueY = footerTop + footerHeight * 0.38f
            val bottomRowLabelY = footerTop + footerHeight * 0.64f
            val bottomRowValueY = footerTop + footerHeight * 0.80f

            canvas.drawText("DISTANCE", col1, topRowLabelY, statsLabelPaint)
            canvas.drawText(String.format(Locale.US, "%.2f km", runningDistanceM / 1000.0), col1, topRowValueY, statsValuePaint)

            canvas.drawText("TIME ON ROAD", col2, topRowLabelY, statsLabelPaint)
            canvas.drawText(formatDuration(runningDurationS), col2, topRowValueY, statsValuePaint)

            canvas.drawText("AVG SPEED", col1, bottomRowLabelY, statsLabelPaint)
            canvas.drawText(avgSpeedText, col1, bottomRowValueY, statsValuePaint)

            canvas.drawText("MAX SPEED", col2, bottomRowLabelY, statsLabelPaint)
            canvas.drawText(maxSpeedText, col2, bottomRowValueY, statsValuePaint)
        }

        private fun formatDuration(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return String.format(Locale.US, "%02d:%02d", hours, minutes)
        }
    }
}
