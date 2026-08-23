package com.ayushkataria.bikeryde.planning

/** Which model produced a [RidePlan]'s suggestions (design doc §5.5). */
enum class PlanSource {
    LOCAL,
    CLOUD
}

data class WeatherSnapshot(
    val dateRangeStart: Long,
    val dateRangeEnd: Long,
    val summary: String,
    val highC: Double?,
    val lowC: Double?,
    val precipitationChancePercent: Int?
)

data class PlaceSuggestion(
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val notes: String?
)

data class PlanSuggestions(
    val food: List<PlaceSuggestion> = emptyList(),
    val sightseeing: List<PlaceSuggestion> = emptyList(),
    val lodging: List<PlaceSuggestion> = emptyList()
)

/** An AI-assisted plan for a destination and date range, generated locally or via a cloud model. */
data class RidePlan(
    val id: Long,
    val title: String,
    val destination: String,
    val dateRangeStart: Long,
    val dateRangeEnd: Long,
    val weatherSnapshot: WeatherSnapshot?,
    val suggestions: PlanSuggestions,
    val generatedBy: PlanSource
)
