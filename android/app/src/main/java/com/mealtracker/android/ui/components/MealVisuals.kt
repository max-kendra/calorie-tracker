package com.mealtracker.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single source of truth for the fixed icon+color per meal type, shared
 * between JournalScreen (meal-row avatars) and MealDetailScreen (the
 * hero background behind the meal name) so the two stay in sync by
 * construction rather than needing two copies of the same `when`
 * kept manually consistent.
 */
object MealVisuals {
    val iconTint = Color(0xFF5D4037)

    fun iconFor(mealType: String): ImageVector = when (mealType) {
        "breakfast" -> Icons.Filled.LocalCafe
        "lunch" -> Icons.Filled.Restaurant
        "dinner" -> Icons.Filled.DinnerDining
        else -> Icons.Filled.Icecream
    }

    fun backgroundFor(mealType: String): Color = when (mealType) {
        "breakfast" -> Color(0xFFFFE0B2)
        "lunch" -> Color(0xFFC8E6C9)
        "dinner" -> Color(0xFFD1C4E9)
        else -> Color(0xFFFFCCBC)
    }
}