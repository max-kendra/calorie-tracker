package com.mealtracker.android.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

// Matches the color scheme from the original Foodvisor-inspired mockups:
// yellow=fat, red/coral=protein, blue=carbs, brown=fiber.
private val FatColor = Color(0xFFE6B800)
private val ProteinColor = Color(0xFFE8837A)
private val CarbsColor = Color(0xFF7EC8E3)
private val FiberColor = Color(0xFF9C7A54)

/**
 * Lets the user set their daily calorie target and how it splits across
 * Fat/Protein/Carbs/Fiber -- matches the design doc's Macronutrients
 * screen (percentage sliders, hard gate at exactly 100% before saving).
 * Creates the first Goal if none exists yet, otherwise updates the
 * existing one. This is also currently the only way to get an active
 * Goal set up at all, since there's no separate "just calories" screen
 * yet -- this screen covers both.
 */
@Composable
fun MacronutrientsScreen(viewModel: MacronutrientsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

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
        Text("Macronutrients", style = MaterialTheme.typography.headlineSmall)

        if (state.loadError != null) {
            Text(
                "Couldn't load your current goal (starting fresh): ${state.loadError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        OutlinedTextField(
            value = state.kcalTarget,
            onValueChange = viewModel::updateKcalTarget,
            label = { Text("Daily Calorie Target") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

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

        MacroSliderRow("Fat", state.fat, viewModel::updateFatPct, FatColor)
        MacroSliderRow("Protein", state.protein, viewModel::updateProteinPct, ProteinColor)
        MacroSliderRow("Carbs", state.carbs, viewModel::updateCarbsPct, CarbsColor)
        MacroSliderRow("Fiber", state.fiber, viewModel::updateFiberPct, FiberColor)

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
private fun MacroSliderRow(name: String, row: MacroRow, onValueChange: (Double) -> Unit, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$name \u00b7 ${row.grams.roundToInt()}g \u00b7 ${row.kcal.roundToInt()} Cal",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${row.percent.roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = color
            )
        }
        Slider(
            value = row.percent.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            )
        )
    }
}
