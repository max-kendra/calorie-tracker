package com.mealtracker.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.health.HealthConnectPreferences
import kotlinx.coroutines.launch

/**
 * Separate tab for both Health Connect toggles (weight import, nutrition
 * export) -- previously weight-import permission was only requestable
 * from the main Profile screen with no way to turn it back off, and
 * nutrition export didn't exist at all. Both permissions are still
 * requested TOGETHER in one combined prompt (same as onboarding's
 * HealthConnectOnboardingStep) if either toggle is turned on and that
 * permission hasn't been granted yet -- turning a toggle OFF here just
 * flips the local preference (see HealthConnectPreferences), it does
 * NOT revoke the underlying OS permission, which the user can only do
 * from Android's own Health Connect app/settings.
 */
@Composable
fun HealthConnectSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var healthConnectAvailable by remember { mutableStateOf(true) }
    var hasAllPermissions by remember { mutableStateOf(false) }
    var weightImportEnabled by remember { mutableStateOf(HealthConnectPreferences.isWeightImportEnabled(context)) }
    var nutritionExportEnabled by remember { mutableStateOf(HealthConnectPreferences.isNutritionExportEnabled(context)) }

    suspend fun refreshPermissionState() {
        healthConnectAvailable = HealthConnectManager.isAvailable(context)
        hasAllPermissions = healthConnectAvailable && HealthConnectManager.hasAllPermissions(context)
    }

    LaunchedEffect(Unit) { refreshPermissionState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        HealthConnectManager.requestPermissionsContract()
    ) { granted ->
        if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) {
            weightImportEnabled = true
            HealthConnectPreferences.setWeightImportEnabled(context, true)
        }
        if (granted.contains(HealthPermission.getWritePermission(NutritionRecord::class))) {
            nutritionExportEnabled = true
            HealthConnectPreferences.setNutritionExportEnabled(context, true)
        }
        coroutineScope.launch { refreshPermissionState() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Health Connect", style = MaterialTheme.typography.headlineSmall)
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        if (!healthConnectAvailable) {
            Text(
                "Health Connect isn't available on this device. Install it from the " +
                    "Play Store to use either of these.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            HealthConnectToggleRow(
                title = "Import weight",
                subtitle = "Show your weight history from Health Connect on your profile",
                checked = weightImportEnabled,
                onCheckedChange = { checked ->
                    if (checked && !hasAllPermissions) {
                        permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    } else {
                        weightImportEnabled = checked
                        HealthConnectPreferences.setWeightImportEnabled(context, checked)
                    }
                }
            )
            HorizontalDivider()

            HealthConnectToggleRow(
                title = "Export nutrition",
                subtitle = "Send the meals you log here to Health Connect, one entry per meal",
                checked = nutritionExportEnabled,
                onCheckedChange = { checked ->
                    if (checked && !hasAllPermissions) {
                        permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    } else {
                        nutritionExportEnabled = checked
                        HealthConnectPreferences.setNutritionExportEnabled(context, checked)
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HealthConnectToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}