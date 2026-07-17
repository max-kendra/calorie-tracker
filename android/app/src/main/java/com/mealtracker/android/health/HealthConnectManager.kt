package com.mealtracker.android.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Thin read-only wrapper around the Health Connect SDK.
 *
 * Deliberately READ-ONLY: this app is not a place to log weight, and
 * never requests or uses a write permission -- Health Connect (or
 * whatever writes into it, e.g. a smart scale's own app) is the source
 * of truth for weight, and the Profile screen's weight graph/list is
 * just a read-only view onto that (see UserProfile model's docstring on
 * the backend for the related `weight_kg` field's role as a fallback
 * manual value, distinct from this).
 */
object HealthConnectManager {

    // READ_WEIGHT alone only gets us the last 30 days of data, no
    // matter what time range we ask readWeightHistory() for -- that's a
    // Health Connect platform restriction (default read window is 30
    // days before permission was first granted), not a bug in our own
    // query. PERMISSION_READ_HEALTH_DATA_HISTORY lifts that cap. Also
    // needs the matching <uses-permission> in AndroidManifest.xml.
    // See: https://developer.android.com/health-and-fitness/health-connect/read-data
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )

    /**
     * True if the Health Connect app/framework module is present AND
     * up to date enough to use -- distinct from whether WE'VE been
     * granted permission yet (see hasAllPermissions). Callers should
     * check this first and fall back gracefully (e.g. "Health Connect
     * isn't available on this device, install it from Play Store") if
     * false, rather than attempting a client call that will just throw.
     */
    fun isAvailable(context: Context): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context)

    /** Activity-result contract for requesting our permission set --
     * launch it with PERMISSIONS as input, same shape as any other
     * ActivityResultContract (see ProfileOverviewViewModel/ProfileScreen
     * for how this gets wired into a rememberLauncherForActivityResult). */
    fun requestPermissionsContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(context: Context): Boolean {
        val granted = client(context).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    data class WeightEntry(val time: Instant, val kg: Double)

    /**
     * Reads WeightRecord entries in [start, end], oldest first.
     *
     * Assumes hasAllPermissions() has already been confirmed true --
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
}