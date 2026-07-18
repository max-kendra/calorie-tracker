package com.mealtracker.android.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Thin wrapper around the Health Connect SDK. Started out read-only
 * (weight import), now also writes nutrition data per meal - see
 * writeMealNutrition's doc comment for how that works. Both are
 * separately toggle-able (see HealthConnectPreferences) even once
 * permission is granted, since granting Health Connect access and
 * actually wanting either feature turned on are different decisions.
 */
object HealthConnectManager {

    // READ_WEIGHT alone only gets us the last 30 days of data, no
    // matter what time range we ask readWeightHistory() for - that's a
    // Health Connect platform restriction (default read window is 30
    // days before permission was first granted), not a bug in our own
    // query. PERMISSION_READ_HEALTH_DATA_HISTORY lifts that cap. Also
    // needs the matching <uses-permission> in AndroidManifest.xml.
    // See: https://developer.android.com/health-and-fitness/health-connect/read-data
    val WEIGHT_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )

    // WRITE_NUTRITION added alongside the read-only weight permissions
    // per design discussion - both get REQUESTED together, in one
    // combined prompt, at onboarding (see OnboardingScreen's Health
    // Connect step) and again from Settings if skipped there. But
    // CHECKING whether a permission is granted must stay per-feature
    // (see hasWeightPermissions/hasNutritionPermission below) - treating
    // the combined set as all-or-nothing for grant-checking meant that
    // granting weight alone (e.g. before nutrition export existed) would
    // make the weight feature itself look disconnected the moment
    // nutrition was added to the combined set, since it was never
    // granted too. Requesting together is fine; checking together is
    // the bug. Health Connect doesn't have a concept of "nutrition
    // goals" as a shared/writable data type (checked before building
    // this - the calorie-target feature in the Google Health app itself
    // is a setting local to that app, not part of the Health Connect
    // API), so goals are NOT synced here, only actual logged nutrition.
    val NUTRITION_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class)
    )

    val PERMISSIONS: Set<String> = WEIGHT_PERMISSIONS + NUTRITION_PERMISSIONS

    /**
     * True if the Health Connect app/framework module is present AND
     * up to date enough to use - distinct from whether WE'VE been
     * granted permission yet (see hasWeightPermissions/
     * hasNutritionPermission). Callers should check this first and fall
     * back gracefully (e.g. "Health Connect isn't available on this
     * device, install it from Play Store") if false, rather than
     * attempting a client call that will just throw.
     */
    fun isAvailable(context: Context): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    /** Activity-result contract for requesting our permission set -
     * launch it with PERMISSIONS as input, same shape as any other
     * ActivityResultContract (see ProfileOverviewViewModel/ProfileScreen
     * for how this gets wired into a rememberLauncherForActivityResult). */
    fun requestPermissionsContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** Whether ALL of PERMISSIONS (weight + nutrition combined) are
     * granted - only useful for deciding whether the combined
     * onboarding/settings prompt still needs to be shown at all. NOT
     * suitable for gating an individual feature - use
     * hasWeightPermissions or hasNutritionPermission for that instead,
     * see this file's doc comment on NUTRITION_PERMISSIONS for why. */
    suspend fun hasAllPermissions(context: Context): Boolean {
        val granted = client(context).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun hasWeightPermissions(context: Context): Boolean {
        val granted = client(context).permissionController.getGrantedPermissions()
        return granted.containsAll(WEIGHT_PERMISSIONS)
    }

    suspend fun hasNutritionPermission(context: Context): Boolean {
        val granted = client(context).permissionController.getGrantedPermissions()
        return granted.containsAll(NUTRITION_PERMISSIONS)
    }

    data class WeightEntry(val time: Instant, val kg: Double)

    /**
     * Reads WeightRecord entries in [start, end], oldest first.
     *
     * Assumes hasWeightPermissions() has already been confirmed true -
     * Health Connect throws a SecurityException on read without it, and
     * that's deliberately not swallowed here; the caller (ViewModel)
     * should check permission state explicitly first and prompt for it,
     * rather than this function silently returning an empty list on a
     * permission failure that looks identical to "no weight data
     * logged yet."
     */
    suspend fun readWeightHistory(context: Context, start: Instant, end: Instant): List<WeightEntry> {
        val response = client(context).readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return response.records
            .map { WeightEntry(time = it.time, kg = it.weight.inKilograms) }
            .sortedBy { it.time }
    }

    data class MealNutritionTotals(
        val kcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
        val saturatedFatG: Double?,
        val sugarG: Double?,
        val fiberG: Double?,
        val sodiumMg: Double?
    )

    /** Maps this app's meal_type strings to Health Connect's own
     * MEAL_TYPE_* constants - falls back to UNKNOWN for anything that
     * doesn't match one of the four Health Connect actually has (it has
     * no equivalent of e.g. a fifth custom meal type). */
    private fun healthConnectMealType(mealType: String): Int = when (mealType.lowercase()) {
        "breakfast" -> androidx.health.connect.client.records.MealType.MEAL_TYPE_BREAKFAST
        "lunch" -> androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH
        "dinner" -> androidx.health.connect.client.records.MealType.MEAL_TYPE_DINNER
        "snack" -> androidx.health.connect.client.records.MealType.MEAL_TYPE_SNACK
        else -> androidx.health.connect.client.records.MealType.MEAL_TYPE_UNKNOWN
    }

    /** Deterministic per-(date, mealType) ID - NOT randomly generated
     * and NOT stored anywhere on our own side. This is the actual
     * answer to "how do we get the ID of the record we create so we can
     * update/delete it later": we don't need to store one at all.
     * Health Connect's own clientRecordId/clientRecordVersion mechanism
     * (see Metadata below) treats an insert with the SAME
     * clientRecordId and a HIGHER clientRecordVersion as a replacement
     * of the previous record, not a duplicate - built specifically for
     * "keep an external app's data in sync as the source changes"
     * situations like this one. */
    private fun clientRecordId(date: LocalDate, mealType: String): String =
        "mealtracker-$date-${mealType.lowercase()}"

    /**
     * Upserts ONE NutritionRecord representing the SUMMED totals of
     * every log in this (date, mealType) - per design discussion,
     * meals are the sync unit here, not individual items, since that's
     * this app's own unit of logging and maps directly onto Health
     * Connect's own per-meal `mealType` field. Call this after ANY
     * add/edit/delete that changes what's logged in a meal, passing the
     * freshly-recomputed totals - NOT an incremental adjustment to a
     * previous write, since Health Connect has no "modify this record"
     * operation, only insert/delete. Re-inserting with the same
     * clientRecordId and a newer clientRecordVersion (current time in
     * millis, which trivially satisfies "newer than last time") is what
     * makes this behave as an update rather than a duplicate.
     *
     * Uses the whole day as the record's start/end interval, since this
     * app only tracks a log's DATE and meal type, not a precise time it
     * was eaten - inventing a specific time (e.g. "breakfast is always
     * 8am") would be less honest than being explicit that we don't
     * actually know when within the day it happened.
     */
    suspend fun writeMealNutrition(
        context: Context,
        date: LocalDate,
        mealType: String,
        totals: MealNutritionTotals
    ) {
        val zone = ZoneId.systemDefault()
        val startTime = date.atStartOfDay(zone).toInstant()
        val endTime = date.plusDays(1).atStartOfDay(zone).toInstant()

        val record = NutritionRecord(
            startTime = startTime,
            startZoneOffset = zone.rules.getOffset(startTime),
            endTime = endTime,
            endZoneOffset = zone.rules.getOffset(endTime),
            mealType = healthConnectMealType(mealType),
            energy = totals.kcal?.let { Energy.kilocalories(it) },
            protein = totals.proteinG?.let { Mass.grams(it) },
            totalCarbohydrate = totals.carbsG?.let { Mass.grams(it) },
            totalFat = totals.fatG?.let { Mass.grams(it) },
            saturatedFat = totals.saturatedFatG?.let { Mass.grams(it) },
            sugar = totals.sugarG?.let { Mass.grams(it) },
            dietaryFiber = totals.fiberG?.let { Mass.grams(it) },
            sodium = totals.sodiumMg?.let { Mass.milligrams(it) },
            metadata = Metadata.manualEntry(
                clientRecordId = clientRecordId(date, mealType),
                clientRecordVersion = System.currentTimeMillis()
            )
        )
        client(context).insertRecords(listOf(record))
    }

    /**
     * Removes this meal's NutritionRecord entirely - call when a meal
     * becomes empty (its last log deleted), rather than leaving a
     * stale zero-calorie entry behind in Health Connect. Deletes by
     * clientRecordId, same mechanism as the upsert above - no stored
     * Health Connect UID needed here either.
     */
    suspend fun deleteMealNutrition(context: Context, date: LocalDate, mealType: String) {
        client(context).deleteRecords(
            NutritionRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(clientRecordId(date, mealType))
        )
    }
}