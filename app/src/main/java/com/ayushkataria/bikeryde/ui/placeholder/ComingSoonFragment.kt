package com.ayushkataria.bikeryde.ui.placeholder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R

/**
 * Generic "coming soon" screen reused for every feature that only needs a placeholder today
 * (multi-day rides, AI planning, static image/animation export, fuel log). Takes its title and
 * description as nav arguments rather than being subclassed per feature.
 */
class ComingSoonFragment : Fragment(R.layout.fragment_coming_soon) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = arguments?.getString(ARG_TITLE).orEmpty()
        val description = arguments?.getString(ARG_DESCRIPTION).orEmpty()

        view.findViewById<TextView>(R.id.topBarTitle).text = title
        view.findViewById<View>(R.id.topBarBack).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<TextView>(R.id.featureTitleText).text = title
        view.findViewById<TextView>(R.id.featureDescriptionText).text = description
    }

    companion object {
        const val ARG_TITLE = "featureTitle"
        const val ARG_DESCRIPTION = "featureDescription"
    }
}
