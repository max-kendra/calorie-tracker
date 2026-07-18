package com.mealtracker.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.health.HealthConnectPreferences

/**
 * Onboarding's Health Connect step -- requests BOTH the weight-read and
 * nutrition-write permission together, in one combined prompt, per
 * design discussion ("nutrition export should get asked at the same
 * time as weight import too"). Unlike the other four onboarding steps
 * (see OnboardingScreen's doc comment: "no skip option... required, not
 * optional"), this one IS skippable -- Health Connect might not be
 * available on this device at all, or the user might simply not want
 * either feature, and neither of those should block using the rest of
 * the app the way an incomplete profile/goal would. Both features can
 * still be turned on later from Settings (see the Health Connect
 * settings screen), which re-requests permission the same way if it
 * wasn't granted here.
 */
@Composable
fun HealthConnectOnboardingStep(onDone: () -> Unit) {
    val context = LocalContext.current
    var healthConnectAvailable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        healthConnectAvailable = HealthConnectManager.isAvailable(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        HealthConnectManager.requestPermissionsContract()
    ) { granted ->
        // Enables whichever of the two were actually granted -- a
        // partial grant (e.g. the user allows weight but denies
        // nutrition in the system prompt) shouldn't silently turn on
        // the feature they just said no to.
        if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) {
            HealthConnectPreferences.setWeightImportEnabled(context, true)
        }
        if (granted.contains(HealthPermission.getWritePermission(NutritionRecord::class))) {
            HealthConnectPreferences.setNutritionExportEnabled(context, true)
        }
        onDone()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Connect Health Connect", style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
        if (healthConnectAvailable) {
            Text(
                "Import your weight history from Health Connect, and export the meals " +
                    "you log here back into Health Connect. You can change either of these " +
                    "any time in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))
            Button(
                onClick = { permissionLauncher.launch(HealthConnectManager.PERMISSIONS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect Health Connect")
            }
        } else {
            Text(
                "Health Connect isn't available on this device. You can install it later " +
                    "from the Play Store and connect it from Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = onDone) {
            Text("Not now")
        }
    }
}