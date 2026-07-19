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
import androidx.compose.runtime.LaunchedEffect
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

// Matches the color scheme from the original Foodvisor-inspired mockups:
// yellow=fat, red/coral=protein, blue=carbs, brown=fiber.
private val FatColor = Color(0xFFE6B800)
private val ProteinColor = Color(0xFFE8837A)
private val CarbsColor = Color(0xFF7EC8E3)
private val FiberColor = Color(0xFF9C7A54)

@Composable
fun MacronutrientsScreen(
    viewModel: MacronutrientsViewModel = viewModel(),
    onNavigateToProfile: () -> Unit = {},
    onBack: () -> Unit = {},
    onSaved: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) onSaved?.invoke()
    }

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
            text = { Text("You can save your changes if all your macros add up to 100%.") },
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

    if (state.goalMissing) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Macronutrients", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Set up your calorie goal in Profile first", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(onClick = onNavigateToProfile) {
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
            Text("Macronutrients", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (state.loadError != null) {
                Text(
                    "Couldn't load your goal: ${state.loadError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(
                    segments = listOf(
                        state.fatPct / 100f to FatColor,
                        state.carbsPct / 100f to CarbsColor,
                        state.fiberPct / 100f to FiberColor,
                        state.proteinPct / 100f to ProteinColor
                    ),
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

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

            Text(
                text = "Daily goal: ${state.kcalTarget.roundToInt()} Cal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

            if (!state.isValidTotal) {
                Text(
                    text = "The total should be 100%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            }

            PercentSliderRow("Fat", "${state.fat.grams}g \u00b7 ${state.fat.kcal} Cal", state.fat.percent, FatColor, viewModel::updateFatPct)
            PercentSliderRow("Carbs", "${state.carbs.grams}g \u00b7 ${state.carbs.kcal} Cal", state.carbs.percent, CarbsColor, viewModel::updateCarbsPct)
            PercentSliderRow("Fiber", "${state.fiber.grams}g \u00b7 ${state.fiber.kcal} Cal", state.fiber.percent, FiberColor, viewModel::updateFiberPct)
            PercentSliderRow("Protein", "${state.protein.grams}g \u00b7 ${state.protein.kcal} Cal", state.protein.percent, ProteinColor, viewModel::updateProteinPct)

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