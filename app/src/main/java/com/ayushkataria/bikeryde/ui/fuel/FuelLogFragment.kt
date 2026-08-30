package com.ayushkataria.bikeryde.ui.fuel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.fuel.FuelLog
import com.ayushkataria.bikeryde.fuel.FuelRepository
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** The design doc's §5.4 fuel log: fill-up history plus cost/mileage trend charts, most recent
 * fill-up first. Adding a fill-up opens [AddFuelLogFragment]; price/liter and mileage since the
 * last fill are derived, never entered directly (see [FuelRepository.addFuelLog]). */
class FuelLogFragment : Fragment(R.layout.fragment_fuel_log) {

    private lateinit var repository: FuelRepository
    private lateinit var emptyStateText: View
    private lateinit var trendsSection: View
    private lateinit var historyLabel: View
    private lateinit var entriesContainer: ViewGroup
    private lateinit var mileageTrendChart: TrendLineChartView

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.home_card_fuel_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        repository = FuelRepository(requireContext())
        emptyStateText = view.findViewById(R.id.emptyStateText)
        trendsSection = view.findViewById(R.id.trendsSection)
        historyLabel = view.findViewById(R.id.historyLabel)
        entriesContainer = view.findViewById(R.id.entriesContainer)
        mileageTrendChart = view.findViewById(R.id.mileageTrendChart)

        val primaryColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        val labelColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        mileageTrendChart.setLineColor(primaryColor)
        mileageTrendChart.setLabelColor(labelColor)
        mileageTrendChart.setEmptyTextColor(labelColor)

        view.findViewById<View>(R.id.addFuelButton).setOnClickListener {
            findNavController().navigate(R.id.action_fuelLog_to_addFuelLog)
        }
    }

    override fun onResume() {
        super.onResume()
        loadFuelLogs()
    }

    private fun loadFuelLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val logs = repository.getFuelLogs()
            emptyStateText.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            historyLabel.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE

            // Charts read left-to-right as time moves forward, so oldest-first (logs come back newest-first).
            // The first-ever fill-up never has a mileage figure (no prior odometer to measure from).
            val mileagePoints = logs.asReversed().mapNotNull { it.mileageSinceLastKm }
            trendsSection.visibility = if (mileagePoints.size >= 2) View.VISIBLE else View.GONE
            if (mileagePoints.size >= 2) {
                mileageTrendChart.submit(mileagePoints) { getString(R.string.fuel_log_mileage_format, formatDecimal(it, 1)) }
            }

            entriesContainer.removeAllViews()
            logs.forEach { log -> entriesContainer.addView(buildRow(log)) }
        }
    }

    private fun buildRow(log: FuelLog): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_fuel_log_row, entriesContainer, false)
        row.findViewById<TextView>(R.id.rowPrimaryText).text = getString(
            R.string.fuel_log_row_primary_format,
            formatDecimal(log.cost, 2),
            formatDecimal(log.litersFilled, 2)
        )
        row.findViewById<TextView>(R.id.rowDateText).text = getString(
            R.string.fuel_log_row_date_format,
            dateFormat.format(log.timestamp),
            formatDecimal(log.odoKm, 0)
        )
        val mileageText = log.mileageSinceLastKm?.let { getString(R.string.fuel_log_mileage_format, formatDecimal(it, 1)) }
            ?: getString(R.string.fuel_log_mileage_unset)
        row.findViewById<TextView>(R.id.rowDerivedText).text = getString(
            R.string.fuel_log_row_derived_format,
            formatDecimal(log.pricePerLiter, 2),
            mileageText
        )
        row.findViewById<View>(R.id.rowMenuButton).setOnClickListener { anchor -> showRowMenu(anchor, log) }
        return row
    }

    private fun showRowMenu(anchor: View, log: FuelLog) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_DELETE, 0, R.string.ride_history_action_delete)
            setOnMenuItemClickListener {
                showDeleteConfirm(log)
                true
            }
        }.show()
    }

    private fun showDeleteConfirm(log: FuelLog) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fuel_delete_confirm_title)
            .setMessage(R.string.fuel_delete_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deleteFuelLog(log.id)
                    loadFuelLogs()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatDecimal(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    companion object {
        private const val MENU_DELETE = 1
    }
}
