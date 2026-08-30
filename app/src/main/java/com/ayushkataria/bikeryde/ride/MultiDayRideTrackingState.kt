package com.ayushkataria.bikeryde.ride

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live [MultiDayRideUiState] published by [MultiDayRideTrackingService] — mirrors
 * [RideTrackingState]'s role for the single-day flow. */
object MultiDayRideTrackingState {
    private val _state = MutableStateFlow(MultiDayRideUiState())
    val state: StateFlow<MultiDayRideUiState> = _state.asStateFlow()

    fun update(newState: MultiDayRideUiState) {
        _state.value = newState
    }
}
