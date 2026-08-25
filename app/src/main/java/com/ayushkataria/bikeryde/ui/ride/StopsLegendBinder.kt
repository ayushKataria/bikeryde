package com.ayushkataria.bikeryde.ui.ride

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isEmpty
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.RideEvent
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Stop color/label mapping and legend-row rendering, shared by the live tracking screen
 * ([SingleDayRideFragment]) and the read-only ride-history detail screen ([RideDetailFragment]) —
 * both show the same route + stop markers + legend for a ride, live or historical.
 */
class StopsLegendBinder(private val context: Context, private val legendContainer: LinearLayout) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun colorForStopAction(action: RideEventAction): Int = ContextCompat.getColor(
        context,
        when (action) {
            RideEventAction.START -> R.color.status_tracking
            RideEventAction.END -> R.color.md_error
            RideEventAction.PAUSE, RideEventAction.RESUME -> R.color.status_paused
        }
    )

    fun stopActionLabel(action: RideEventAction): String = when (action) {
        RideEventAction.START -> context.getString(R.string.stop_action_start)
        RideEventAction.PAUSE -> context.getString(R.string.stop_action_pause)
        RideEventAction.RESUME -> context.getString(R.string.stop_action_resume)
        RideEventAction.END -> context.getString(R.string.stop_action_end)
    }

    fun render(events: List<RideEvent>) {
        legendContainer.removeAllViews()
        events.forEach { event ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (legendContainer.isEmpty()) 0 else dpToPx(10) }
            }
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8))
                background = ContextCompat.getDrawable(context, R.drawable.bg_status_dot)
                backgroundTintList = ColorStateList.valueOf(colorForStopAction(event.action))
            }
            val label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(10)
                }
                text = context.getString(
                    R.string.stop_legend_row_format,
                    stopActionLabel(event.action),
                    event.placeName ?: context.getString(R.string.unknown_location),
                    timeFormat.format(event.timestamp)
                )
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                textSize = 14f
            }
            row.addView(dot)
            row.addView(label)
            legendContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
