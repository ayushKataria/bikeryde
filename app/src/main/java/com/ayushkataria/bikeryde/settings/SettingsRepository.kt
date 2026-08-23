package com.ayushkataria.bikeryde.settings

import android.content.Context

/**
 * Persists [AppSettings] locally. Backed by plain [android.content.SharedPreferences] for now;
 * the design doc calls for EncryptedSharedPreferences for the API keys specifically (§5.7) —
 * swap the backing store here once that's wired in, callers are unaffected either way.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): AppSettings = AppSettings(
        apiKeys = ApiKeys(
            placesKey = prefs.getString(KEY_PLACES, null),
            mapsKey = prefs.getString(KEY_MAPS, null),
            cloudAiKey = prefs.getString(KEY_CLOUD_AI, null)
        ),
        aiMode = prefs.getString(KEY_AI_MODE, null)?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
            ?: AiMode.LOCAL,
        driveSyncEnabled = prefs.getBoolean(KEY_DRIVE_SYNC, false),
        units = prefs.getString(KEY_UNITS, null)?.let { runCatching { Units.valueOf(it) }.getOrNull() }
            ?: Units.KM
    )

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_PLACES, settings.apiKeys.placesKey)
            .putString(KEY_MAPS, settings.apiKeys.mapsKey)
            .putString(KEY_CLOUD_AI, settings.apiKeys.cloudAiKey)
            .putString(KEY_AI_MODE, settings.aiMode.name)
            .putBoolean(KEY_DRIVE_SYNC, settings.driveSyncEnabled)
            .putString(KEY_UNITS, settings.units.name)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bikeryde_settings"
        private const val KEY_PLACES = "api_key_places"
        private const val KEY_MAPS = "api_key_maps"
        private const val KEY_CLOUD_AI = "api_key_cloud_ai"
        private const val KEY_AI_MODE = "ai_mode"
        private const val KEY_DRIVE_SYNC = "drive_sync_enabled"
        private const val KEY_UNITS = "units"
    }
}
