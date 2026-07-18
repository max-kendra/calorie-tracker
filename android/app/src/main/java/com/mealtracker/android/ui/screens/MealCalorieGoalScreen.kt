package com.mealtracker.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.mealtracker.android.ui.components.PercentSliderRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.ui.components.DonutChart
import kotlin.math.roundToInt

// Matches the color scheme from the original Foodvisor-inspired mockups
// for meal cards: teal breakfast, coral lunch, olive green dinner,
// mustard-yellow snacks.
private val BreakfastColor = Color(0xFF2A9D8F)
private val LunchColor = Color(0xFFE76F51)
private val DinnerColor = Color(0xFF8AB17D)
private val SnackColor = Color(0xFFE9C46A)

private fun colorFor(mealType: String): Color = when (mealType) {
    "breakfast" -> BreakfastColor
    "lunch" -> LunchColor
    "dinner" -> DinnerColor
    "snack" -> SnackColor
    else -> Color.Gray
}

/**
 * Lets the user split their daily calorie goal across Breakfast/Lunch/
 * Dinner/Snacks - matches the design doc's Meal Calorie Goal screen.
 * Same hard-gate-at-100% pattern, donut visualization, reset, and
 * discard-changes confirmation as the Macronutrients screen. Requires
 * an active Goal to already exist (set one up via Macronutrients first,
 * since that's currently the only place a Goal gets created).
 */
@Composable
fun MealCalorieGoalScreen(
    viewModel: MealCalorieGoalViewModel = viewModel(),
    onNavigateToSetGoal: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun handleBack() {
        if (!state.isValidTotal && state.hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Are you sure you want to discard your changes?") },
            text = { Text("You can save your changes if all your meals add up to 100%.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text("Discard changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.goalId == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Meal Calorie Goal", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Set your calorie & macro goal first", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(onClick = onNavigateToSetGoal) {
                    Text("Go to Profile")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { handleBack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Meal Calorie Goal", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(
                    segments = state.rows.map { it.percent / 100f to colorFor(it.mealType) },
                    centerContent = {
                        Text(
                            text = "${state.totalPct}%",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (state.isValidTotal) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                )
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

            if (!state.isValidTotal) {
                Text(
                    text = "The total should be 100%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            }

            state.rows.forEach { row ->
                PercentSliderRow(
                    label = row.displayName,
                    valueText = "${row.kcal(state.kcalTarget)} Cal",
                    percent = row.percent,
                    color = colorFor(row.mealType),
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

            if (state.hasUnsavedChanges) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { viewModel.reset() }) {
                        Text("Reset changes")
                    }
                }
            }
        }
    }
}