package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholders for the remaining bottom-nav destinations -- build these
 * out one at a time following the design doc's screen specs. Journal is
 * the natural next one after this skeleton is confirmed working, since
 * it's the daily-use core.
 */

@Composable
fun JournalScreen() {
    PlaceholderScreen("Journal — coming next")
}

@Composable
fun MealPlanScreen() {
    PlaceholderScreen("Meal Plan — TBD")
}

@Composable
fun ProfileScreen() {
    PlaceholderScreen("Profile / Goals — TBD")
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
