package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Remaining placeholder bottom-nav destination - Journal, Profile,
 * Macronutrients, and Meal Calorie Goal all have real implementations
 * now. Meal Plan is next.
 */
@Composable
fun MealPlanScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Meal Plan — TBD", style = MaterialTheme.typography.titleLarge)
    }
}
