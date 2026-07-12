package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholders for the remaining bottom-nav destinations -- build these
 * out one at a time following the design doc's screen specs. Journal now
 * has a real implementation (see JournalScreen.kt). Profile has a real
 * entry point to the Macronutrients goal-setting screen, but is
 * otherwise still a placeholder -- Meal Plan is next.
 */

@Composable
fun MealPlanScreen() {
    PlaceholderScreen("Meal Plan — TBD")
}

@Composable
fun ProfileScreen(
    onNavigateToMacronutrients: () -> Unit,
    onNavigateToMealCalorieGoal: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Profile / Goals — TBD", style = MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(onClick = onNavigateToMacronutrients) {
            Text("Set Calorie & Macro Goals")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(onClick = onNavigateToMealCalorieGoal) {
            Text("Set Meal Calorie Split")
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
