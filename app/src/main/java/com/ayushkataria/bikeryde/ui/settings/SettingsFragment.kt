package com.ayushkataria.bikeryde.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.settings.AiMode
import com.ayushkataria.bikeryde.settings.ApiKeys
import com.ayushkataria.bikeryde.settings.AppSettings
import com.ayushkataria.bikeryde.settings.SettingsRepository
import com.ayushkataria.bikeryde.settings.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var repository: SettingsRepository

    private lateinit var placesKeyInput: TextInputEditText
    private lateinit var cloudAiKeyInput: TextInputEditText
    private lateinit var aiModeToggleGroup: MaterialButtonToggleGroup
    private lateinit var unitsToggleGroup: MaterialButtonToggleGroup
    private lateinit var driveSyncSwitch: MaterialSwitch

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = SettingsRepository(requireContext())

        view.findViewById<TextView>(R.id.topBarTitle).text = getString(R.string.home_settings_content_description)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        placesKeyInput = view.findViewById(R.id.placesKeyInput)
        cloudAiKeyInput = view.findViewById(R.id.cloudAiKeyInput)
        aiModeToggleGroup = view.findViewById(R.id.aiModeToggleGroup)
        unitsToggleGroup = view.findViewById(R.id.unitsToggleGroup)
        driveSyncSwitch = view.findViewById(R.id.driveSyncSwitch)

        bindSettings(repository.getSettings())

        view.findViewById<MaterialButton>(R.id.syncNowButton).setOnClickListener {
            Toast.makeText(requireContext(), R.string.settings_sync_coming_soon, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            repository.saveSettings(collectSettings())
            Toast.makeText(requireContext(), R.string.settings_saved_confirmation, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun bindSettings(settings: AppSettings) {
        placesKeyInput.setText(settings.apiKeys.placesKey.orEmpty())
        cloudAiKeyInput.setText(settings.apiKeys.cloudAiKey.orEmpty())
        aiModeToggleGroup.check(
            if (settings.aiMode == AiMode.CLOUD) R.id.aiModeCloudButton else R.id.aiModeLocalButton
        )
        unitsToggleGroup.check(
            if (settings.units == Units.MI) R.id.unitsMiButton else R.id.unitsKmButton
        )
        driveSyncSwitch.isChecked = settings.driveSyncEnabled
    }

    private fun collectSettings(): AppSettings = AppSettings(
        apiKeys = ApiKeys(
            placesKey = placesKeyInput.text?.toString()?.trim()?.ifEmpty { null },
            cloudAiKey = cloudAiKeyInput.text?.toString()?.trim()?.ifEmpty { null }
        ),
        aiMode = if (aiModeToggleGroup.checkedButtonId == R.id.aiModeCloudButton) AiMode.CLOUD else AiMode.LOCAL,
        driveSyncEnabled = driveSyncSwitch.isChecked,
        units = if (unitsToggleGroup.checkedButtonId == R.id.unitsMiButton) Units.MI else Units.KM
    )
}
