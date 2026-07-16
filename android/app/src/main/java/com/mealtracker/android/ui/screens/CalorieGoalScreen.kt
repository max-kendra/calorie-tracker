package com.mealtracker.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val ACTIVITY_LEVELS = listOf(
    "sedentary" to "Sedentary",
    "light" to "Light",
    "moderate" to "Moderate",
    "active" to "Active",
    "very_active" to "Very Active"
)

private val GOAL_TYPES = listOf(
    "lose" to "Lose weight",
    "maintain" to "Maintain",
    "gain" to "Gain weight"
)

@Composable
fun CalorieGoalScreen(
    viewModel: CalorieGoalViewModel = viewModel(),
    onBack: () -> Unit,
    // Fires on goalSaveSuccess specifically -- NOT state.saveSuccess,
    // which just means the TDEE inputs (weight/activity/goal type) were
    // saved to the profile before calculating. The actual "done with
    // this step" moment for onboarding purposes is the goal itself
    // being saved via "Use as my Calorie Goal".
    onSaved: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.goalSaveSuccess) {
        if (state.goalSaveSuccess) onSaved?.invoke()
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Calorie goal", style = MaterialTheme.typography.headlineSmall)
        }

        if (state.loadError != null) {
            Text(
                state.loadError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.heightCm == null || state.age == null) {
            Text(
                "Set your height and age on My Profile first -- both are needed for this calculation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        OutlinedTextField(
            value = state.weightKg,
            onValueChange = viewModel::updateWeightKg,
            label = { Text("Current weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Text("Activity level", style = MaterialTheme.typography.labelLarge)
        ChipRow(ACTIVITY_LEVELS, state.activityLevel, viewModel::updateActivityLevel)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Text("Goal", style = MaterialTheme.typography.labelLarge)
        ChipRow(GOAL_TYPES, state.goalType, viewModel::updateGoalType)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        Button(
            onClick = { viewModel.saveInputsThenCalculate() },
            enabled = state.isProfileComplete && !state.isCalculating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isCalculating) "Calculating..." else "Calculate My Calorie Goal")
        }

        if (state.calcError != null) {
            Text(
                "Couldn't calculate: ${state.calcError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val result = state.calcResult
        if (result != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("BMR: ${result.bmr} kcal", style = MaterialTheme.typography.bodyMedium)
            Text("TDEE: ${result.tdee} kcal", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Suggested range: ${result.kcalLow}\u2013${result.kcalHigh} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result.floorApplied) {
                Text(
                    "Note: the low end was raised to the 1500 kcal/day safety floor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.decrementKcalTarget() }) {
                    Text("\u2212", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "${state.editableKcalTarget} Cal",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(onClick = { viewModel.incrementKcalTarget() }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }

            if (state.belowSafetyFloor) {
                Text(
                    "\u26a0\ufe0f Going below 1500 kcal/day isn't recommended without medical supervision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(
                onClick = { viewModel.saveAsGoal() },
                enabled = !state.isSavingGoal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isSavingGoal) "Saving..." else "Use as my Calorie Goal")
            }
            if (state.goalSaveError != null) {
                Text(
                    "Couldn't save: ${state.goalSaveError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.goalSaveSuccess) {
                Text("\u2705 Goal saved", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}