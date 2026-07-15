package com.mealtracker.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.ui.components.WeightLineChart
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    viewModel: ProfileOverviewViewModel = viewModel(),
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        HealthConnectManager.requestPermissionsContract()
    ) {
        // Result itself (which permissions were granted) isn't used
        // directly -- simplest to just re-check overall state, which
        // also covers the "user denied" case without extra branching.
        viewModel.refreshHealthConnectState(context)
    }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ----- Header: avatar + name + settings -----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // No image-loading library wired up yet (see
                // profilePicPath, currently unused here) -- falls back
                // to a plain icon regardless of whether a photo exists.
                // Swap in a real image loader (e.g. Coil) to render
                // state.profilePicPath when this needs to show an
                // actual photo.
                Icon(
                    Icons.Filled.Person,
                    contentDescription = "Profile picture",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Text(
                state.name?.takeIf { it.isNotBlank() } ?: "Your profile",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        if (state.loadError != null) {
            Text(
                state.loadError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // ----- Weight goal summary -----
        WeightGoalSummary(
            startingWeightKg = state.startingWeightKg,
            currentWeightKg = state.currentWeightKg,
            goalWeightKg = state.goalWeightKg
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        // ----- Health Connect gating -----
        when {
            !state.healthConnectAvailable -> {
                Text(
                    "Health Connect isn't available on this device. Install it from " +
                        "the Play Store to see your weight history here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            !state.healthConnectPermissionGranted -> {
                Text(
                    "Connect Health Connect to see your weight trend here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                Button(
                    onClick = { healthConnectPermissionLauncher.launch(HealthConnectManager.PERMISSIONS) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect Health Connect")
                }
            }
            else -> {
                // ----- Range selector -----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WeightRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.selectedRange == range,
                            onClick = { viewModel.selectRange(context, range) },
                            label = { Text(range.label) }
                        )
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

                if (state.isLoadingWeights) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    WeightLineChart(
                        points = state.weightHistory.map { it.time to it.kg },
                        goalKg = state.goalWeightKg
                    )
                }

                if (state.weightsError != null) {
                    Text(
                        state.weightsError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Weights saved", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

                if (state.weightHistory.isEmpty()) {
                    Text(
                        "No weight entries in this range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                    // Most recent first for the list (readWeightHistory
                    // returns oldest-first, which is what the chart wants).
                    state.weightHistory.sortedByDescending { it.time }.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${formatKg(entry.kg)}kg",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                dateFormatter.format(entry.time.atZone(ZoneId.systemDefault())),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightGoalSummary(
    startingWeightKg: Double?,
    currentWeightKg: Double?,
    goalWeightKg: Double?
) {
    if (startingWeightKg == null && currentWeightKg == null && goalWeightKg == null) {
        Text(
            "Set a starting and goal weight in Settings \u2192 Weight goal to see your progress here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WeightStat("Start weight", startingWeightKg)
        WeightStat("Current weight", currentWeightKg)
        WeightStat("Goal weight", goalWeightKg)
    }

    val diff = if (currentWeightKg != null && goalWeightKg != null) currentWeightKg - goalWeightKg else null
    if (diff != null) {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        val remainingText = when {
            kotlin.math.abs(diff) < 0.05 -> "You've reached your goal weight \uD83C\uDF89"
            diff > 0 -> "${formatKg(diff)}kg to go"
            else -> "${formatKg(-diff)}kg past your goal"
        }
        Text(
            remainingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun WeightStat(label: String, kg: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (kg != null) "${formatKg(kg)}kg" else "\u2013",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatKg(kg: Double): String {
    // One decimal place, trimmed to a whole number when exact (e.g.
    // "88" instead of "88.0") -- matches how the design inspiration
    // displays weights.
    val rounded = (kg * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}