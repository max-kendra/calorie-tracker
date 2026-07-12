package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/**
 * Lets the user split their daily calorie goal across Breakfast/Lunch/
 * Dinner/Snacks -- matches the design doc's Meal Calorie Goal screen.
 * Same hard-gate-at-100% pattern as the Macronutrients screen. Requires
 * an active Goal to already exist (set one up via Macronutrients first,
 * since that's currently the only place a Goal gets created).
 */
@Composable
fun MealCalorieGoalScreen(
    viewModel: MealCalorieGoalViewModel = viewModel(),
    onNavigateToSetGoal: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.goalId == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Set your calorie & macro goal first",
                style = MaterialTheme.typography.titleMedium
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onNavigateToSetGoal) {
                Text("Go to Macronutrients")
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Meal Calorie Goal", style = MaterialTheme.typography.headlineSmall)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        Text(
            text = "Total: ${state.totalPct.roundToInt()}%" +
                if (!state.isValidTotal) "  (must equal exactly 100%)" else "",
            style = MaterialTheme.typography.titleMedium,
            color = if (state.isValidTotal) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        state.rows.forEach { row ->
            MealSliderRow(
                row = row,
                kcalTarget = state.kcalTarget,
                onValueChange = { viewModel.updatePercent(row.mealType, it) }
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        Button(
            onClick = { viewModel.save() },
            enabled = state.isValidTotal && !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isSaving) "Saving..." else "Save")
        }

        if (state.saveError != null) {
            Text(
                "Couldn't save: ${state.saveError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.saveSuccess) {
            Text(
                "\u2705 Saved",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MealSliderRow(row: MealSplitRow, kcalTarget: Double, onValueChange: (Double) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${row.displayName} \u00b7 ${row.kcal(kcalTarget).roundToInt()} Cal",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${row.percent.roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Slider(
            value = row.percent.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = 0f..100f
        )
    }
}
