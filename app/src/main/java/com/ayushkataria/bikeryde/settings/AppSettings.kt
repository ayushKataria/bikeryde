package com.ayushkataria.bikeryde.settings

enum class AiMode {
    LOCAL,
    CLOUD
}

enum class Units {
    KM,
    MI
}

/** BYO API keys for the optional external providers listed in the design doc's API key FAQ (§9). */
data class ApiKeys(
    val placesKey: String? = null,
    val mapsKey: String? = null,
    val cloudAiKey: String? = null
)

data class AppSettings(
    val apiKeys: ApiKeys = ApiKeys(),
    val aiMode: AiMode = AiMode.LOCAL,
    val driveSyncEnabled: Boolean = false,
    val units: Units = Units.KM
)
