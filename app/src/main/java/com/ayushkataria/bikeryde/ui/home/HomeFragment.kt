package com.ayushkataria.bikeryde.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.ui.placeholder.ComingSoonFragment

/** Dashboard hub — the landing screen after onboarding, one card per top-level feature. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindCard(view, R.id.cardSingleDay, R.string.home_card_single_day_title, R.string.home_card_single_day_subtitle) {
            findNavController().navigate(R.id.action_home_to_singleDayRide)
        }
        bindCard(view, R.id.cardMultiDay, R.string.home_card_multi_day_title, R.string.home_card_multi_day_subtitle) {
            findNavController().navigate(R.id.action_home_to_multiDayRide)
        }
        bindCard(view, R.id.cardRideHistory, R.string.home_card_ride_history_title, R.string.home_card_ride_history_subtitle) {
            findNavController().navigate(R.id.action_home_to_rideHistory)
        }
        bindCard(view, R.id.cardFuelLog, R.string.home_card_fuel_title, R.string.home_card_fuel_subtitle) {
            findNavController().navigate(R.id.action_home_to_fuelLog)
        }
        bindCard(view, R.id.cardPlanning, R.string.home_card_planning_title, R.string.home_card_planning_subtitle) {
            navigateToComingSoon(R.string.home_card_planning_title, R.string.coming_soon_desc_planning)
        }

        view.findViewById<View>(R.id.settingsButton).setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
    }

    private fun bindCard(root: View, cardId: Int, titleRes: Int, subtitleRes: Int, onClick: () -> Unit) {
        val card = root.findViewById<View>(cardId)
        card.findViewById<TextView>(R.id.cardTitle).setText(titleRes)
        card.findViewById<TextView>(R.id.cardSubtitle).setText(subtitleRes)
        card.setOnClickListener { onClick() }
    }

    private fun navigateToComingSoon(titleRes: Int, descriptionRes: Int) {
        findNavController().navigate(
            R.id.action_home_to_comingSoon,
            bundleOf(
                ComingSoonFragment.ARG_TITLE to getString(titleRes),
                ComingSoonFragment.ARG_DESCRIPTION to getString(descriptionRes)
            )
        )
    }
}
