package com.mealtracker.android.ui.theme

import android.content.Context

/**
 * User-chosen theme override for the app -- independent of the device's
 * system-wide dark mode setting. SYSTEM (the default) keeps following
 * the OS setting exactly as before; LIGHT/DARK pin the app to one
 * regardless of what the rest of the phone is doing.
 */
enum class ThemePreference(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Plain SharedPreferences rather than DataStore -- this is a single
 * three-way enum value, not a growing set of structured preferences, so
 * a new dependency (and the version-catalog/Gradle-sync churn that
 * comes with it) isn't worth it here. Revisit if this app ends up with
 * enough local settings to want DataStore's type safety/Flow support.
 */
object ThemePreferenceStore {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_THEME_PREFERENCE = "theme_preference"

    fun get(context: Context): ThemePreference {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_THEME_PREFERENCE, null) ?: return ThemePreference.SYSTEM
        return try {
            ThemePreference.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            // Defensive only -- covers a stored value from some future
            // version of this enum that no longer exists, so a bad
            // read doesn't crash the app, just falls back to SYSTEM.
            ThemePreference.SYSTEM
        }
    }

    fun set(context: Context, preference: ThemePreference) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_PREFERENCE, preference.name)
            .apply()
    }
}