package com.ayushkataria.bikeryde.ui.fuel

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.fuel.FuelRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** The design doc's §5.4 fill-up form: odometer, liters, cost — price/liter and mileage since the
 * last fill-up are derived by [FuelRepository], not entered here. */
class AddFuelLogFragment : Fragment(R.layout.fragment_add_fuel_log) {

    private lateinit var repository: FuelRepository
    private lateinit var odoInput: TextInputEditText
    private lateinit var litersInput: TextInputEditText
    private lateinit var costInput: TextInputEditText
    private lateinit var notesInput: TextInputEditText
    private lateinit var saveButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = FuelRepository(requireContext())

        view.findViewById<TextView>(R.id.topBarTitle).setText(R.string.add_fuel_title)
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        odoInput = view.findViewById(R.id.odoInput)
        litersInput = view.findViewById(R.id.litersInput)
        costInput = view.findViewById(R.id.costInput)
        notesInput = view.findViewById(R.id.notesInput)
        saveButton = view.findViewById(R.id.saveButton)

        saveButton.setOnClickListener { onSaveClicked() }
    }

    private fun onSaveClicked() {
        val odoKm = odoInput.text?.toString()?.toDoubleOrNull()
        val liters = litersInput.text?.toString()?.toDoubleOrNull()
        val cost = costInput.text?.toString()?.toDoubleOrNull()

        if (odoKm == null || odoKm <= 0 || liters == null || liters <= 0 || cost == null || cost < 0) {
            Toast.makeText(requireContext(), R.string.add_fuel_invalid_input, Toast.LENGTH_SHORT).show()
            return
        }

        saveButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val mostRecentOdo = repository.getFuelLogs().firstOrNull()?.odoKm
            if (mostRecentOdo != null && odoKm <= mostRecentOdo) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.add_fuel_odo_too_low, formatOdo(mostRecentOdo)),
                    Toast.LENGTH_LONG
                ).show()
                saveButton.isEnabled = true
                return@launch
            }
            repository.addFuelLog(
                timestamp = System.currentTimeMillis(),
                odoKm = odoKm,
                litersFilled = liters,
                cost = cost,
                notes = notesInput.text?.toString()
            )
            Toast.makeText(requireContext(), R.string.add_fuel_saved, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun formatOdo(odoKm: Double): String = String.format(java.util.Locale.US, "%.0f", odoKm)
}
