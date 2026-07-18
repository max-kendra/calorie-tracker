package com.mealtracker.android.health

import android.content.Context

/**
 * Whether weight-import and nutrition-export are actually turned ON,
 * SEPARATELY from whether Health Connect permission has been granted -
 * granting access and wanting a feature enabled are different
 * decisions (see design discussion: both permissions get requested
 * together at onboarding, but a user might still want only one of the
 * two features actually active).
 *
 * Plain SharedPreferences rather than a backend-synced setting - this
 * is inherently a per-device decision (Health Connect itself is
 * per-device, not account-synced), so there's no real benefit to
 * syncing it across devices even if this app's other settings do sync.
 */
object HealthConnectPreferences {
    private const val PREFS_NAME = "health_connect_prefs"
    private const val KEY_WEIGHT_IMPORT_ENABLED = "weight_import_enabled"
    private const val KEY_NUTRITION_EXPORT_ENABLED = "nutrition_export_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isWeightImportEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEIGHT_IMPORT_ENABLED, false)

    fun setWeightImportEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEIGHT_IMPORT_ENABLED, enabled).apply()
    }

    fun isNutritionExportEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NUTRITION_EXPORT_ENABLED, false)

    fun setNutritionExportEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NUTRITION_EXPORT_ENABLED, enabled).apply()
    }
}