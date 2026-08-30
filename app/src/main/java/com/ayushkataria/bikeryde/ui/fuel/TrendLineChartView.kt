package com.ayushkataria.bikeryde.ui.fuel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/**
 * A minimal line-and-dots chart for a short series of fill-up-over-time values (mileage), with
 * each point's actual value labeled above it — deliberately not a full charting library, matching
 * [com.ayushkataria.bikeryde.ui.ride.RouteMapView]'s hand-drawn-Canvas approach for the same
 * reason: this app draws its own simple shapes rather than pulling in a dependency for something
 * this small.
 */
class TrendLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Double> = emptyList()
    private var valueFormatter: (Double) -> String = { it.toString() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = android.graphics.Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 34f
    }

    fun setLineColor(color: Int) {
        linePaint.color = color
        dotPaint.color = color
    }

    fun setLabelColor(color: Int) {
        labelPaint.color = color
    }

    fun setEmptyTextColor(color: Int) {
        emptyPaint.color = color
    }

    fun submit(values: List<Double>, valueFormatter: (Double) -> String) {
        this.values = values
        this.valueFormatter = valueFormatter
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentRight = width - paddingRight.toFloat()
        val contentBottom = height - paddingBottom.toFloat()

        if (values.size < 2) {
            canvas.drawText(EMPTY_TEXT, (contentLeft + contentRight) / 2f, (contentTop + contentBottom) / 2f, emptyPaint)
            return
        }

        val labels = values.map(valueFormatter)
        val labelWidths = labels.map { labelPaint.measureText(it) }
        val dotClearance = DOT_RADIUS + dotStrokePaint.strokeWidth / 2f + EDGE_GAP

        // Reserved on every side so a point sitting exactly at an extreme (highest/lowest value,
        // first/last in the series) still has room for its own label and dot circle — otherwise
        // the outermost points draw flush against, and get visually clipped by, the card's edge.
        val topReserve = dotClearance + LABEL_GAP + (-labelPaint.ascent())
        val leftReserve = maxOf(dotClearance, labelWidths.first() / 2f)
        val rightReserve = maxOf(dotClearance, labelWidths.last() / 2f)

        val plotLeft = contentLeft + leftReserve
        val plotRight = contentRight - rightReserve
        val plotTop = contentTop + topReserve
        val plotBottom = contentBottom - dotClearance

        val minValue = values.min()
        val maxValue = values.max()
        val range = (maxValue - minValue).let { if (it <= 0.0) 1.0 else it }
        val stepX = if (plotRight > plotLeft) (plotRight - plotLeft) / (values.size - 1) else 0f

        val points = values.mapIndexed { index, value ->
            val x = plotLeft + index * stepX
            val y = plotBottom - ((value - minValue) / range).toFloat() * (plotBottom - plotTop)
            PointF(x, y)
        }

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, linePaint)
        points.forEach { canvas.drawCircle(it.x, it.y, DOT_RADIUS, dotPaint) }
        points.forEach { canvas.drawCircle(it.x, it.y, DOT_RADIUS, dotStrokePaint) }

        // Every point gets its actual value labeled above it; only a safety clamp against the
        // content bounds is needed here since the endpoints already have dedicated reserved space.
        points.forEachIndexed { index, point ->
            val halfLabelWidth = labelWidths[index] / 2f
            val labelX = point.x.coerceIn(contentLeft + halfLabelWidth, contentRight - halfLabelWidth)
            canvas.drawText(labels[index], labelX, point.y - DOT_RADIUS - LABEL_GAP, labelPaint)
        }
    }

    companion object {
        private const val DOT_RADIUS = 9f
        private const val EDGE_GAP = 4f
        private const val LABEL_GAP = 10f
        private const val EMPTY_TEXT = "Not enough fill-ups yet"
    }
}
