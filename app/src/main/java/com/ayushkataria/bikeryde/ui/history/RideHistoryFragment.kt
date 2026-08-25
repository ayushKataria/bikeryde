package com.ayushkataria.bikeryde.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ride.Ride
import com.ayushkataria.bikeryde.ride.RideRepository
import com.ayushkataria.bikeryde.ui.ride.RideDetailFragment
import com.ayushkataria.bikeryde.ui.ride.RideStatsFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Lists completed rides, most recent first; tapping one opens [com.ayushkataria.bikeryde.ui.ride.RideDetailFragment]. */
class RideHistoryFragment : Fragment(R.layout.fragment_ride_history) {

    private lateinit var repository: RideRepository
    private lateinit var listContainer: ViewGroup
    private lateinit var emptyStateText: View

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.home_card_ride_history_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        repository = RideRepository(requireContext())
        listContainer = view.findViewById(R.id.rideListContainer)
        emptyStateText = view.findViewById(R.id.emptyStateText)

        loadRides()
    }

    private fun loadRides() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rides = repository.getCompletedRides()
            listContainer.removeAllViews()
            emptyStateText.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
            rides.forEach { ride -> listContainer.addView(buildRow(ride)) }
        }
    }

    private fun buildRow(ride: Ride): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_card, listContainer, false)
        row.findViewById<TextView>(R.id.cardTitle).text = dateFormat.format(ride.startTime)
        row.findViewById<TextView>(R.id.cardSubtitle).text = getString(
            R.string.ride_history_row_subtitle_format,
            RideStatsFormat.distance(ride.totalDistanceM),
            RideStatsFormat.duration(ride.totalDurationS)
        )
        row.setOnClickListener {
            findNavController().navigate(
                R.id.action_rideHistory_to_rideDetail,
                RideDetailFragment.args(ride.id)
            )
        }
        return row
    }
}
