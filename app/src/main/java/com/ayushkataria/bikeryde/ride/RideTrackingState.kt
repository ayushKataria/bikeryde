package com.ayushkataria.bikeryde.ride

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process, single-source-of-truth for the live ride state. [RideTrackingService] is the only
 * writer; the activity/view-model only reads. Living as a singleton keeps the UI decoupled from
 * binding to the service while still getting live updates within the same app process.
 */
object RideTrackingState {
    private val _state = MutableStateFlow(RideUiState())
    val state: StateFlow<RideUiState> = _state.asStateFlow()

    fun update(newState: RideUiState) {
        _state.value = newState
    }
}
