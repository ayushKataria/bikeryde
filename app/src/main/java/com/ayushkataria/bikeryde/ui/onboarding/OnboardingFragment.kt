package com.ayushkataria.bikeryde.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.google.android.material.button.MaterialButton

/** First-run screen: start with a fresh local database, or (later) import a prior Drive export. */
class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.startFreshButton).setOnClickListener {
            findNavController().navigate(R.id.action_onboarding_to_home)
        }
        view.findViewById<MaterialButton>(R.id.importDriveButton).setOnClickListener {
            Toast.makeText(requireContext(), R.string.onboarding_import_coming_soon, Toast.LENGTH_LONG).show()
            findNavController().navigate(R.id.action_onboarding_to_home)
        }
    }
}
