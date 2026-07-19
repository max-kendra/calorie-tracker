package com.mealtracker.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DinnerDining
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mealtracker.android.ui.theme.LocalIsAppDarkTheme

/**
 * Single source of truth for the fixed icon+color per meal type, shared
 * between JournalScreen (meal-row avatars) and MealDetailScreen (the
 * hero background behind the meal name) so the two stay in sync by
 * construction rather than needing two copies of the same `when`
 * kept manually consistent.
 *
 * backgroundFor/iconTint are @Composable now (were plain functions) --
 * dark mode uses darker, desaturated versions of the same hue rather
 * than the literal light-mode pastel, per design discussion ("the
 * pastel hero card colors do have to be a little different [in dark
 * mode]... darker versions of themselves"). iconFor is unaffected by
 * theme (just picks a shape), so it stays a plain function.
 *
 * Reads LocalIsAppDarkTheme (the app's ACTUAL applied theme), not
 * isSystemInDarkTheme() -- see that CompositionLocal's doc comment.
 * Using isSystemInDarkTheme() here directly was the bug being fixed:
 * once the app could be pinned to Light or Dark independent of the
 * device's system setting, these colors kept following the system
 * setting instead of the app's own current theme.
 */
object MealVisuals {
    @Composable
    fun iconTint(): Color = if (LocalIsAppDarkTheme.current) Color(0xFFD7CCC8) else Color(0xFF5D4037)

    fun iconFor(mealType: String): ImageVector = when (mealType) {
        "breakfast" -> Icons.Filled.LocalCafe
        "lunch" -> Icons.Filled.Restaurant
        "dinner" -> Icons.Filled.DinnerDining
        else -> Icons.Filled.Icecream
    }

    @Composable
    fun backgroundFor(mealType: String): Color {
        val dark = LocalIsAppDarkTheme.current
        return when (mealType) {
            "breakfast" -> if (dark) Color(0xFF4A3418) else Color(0xFFFFE0B2)
            "lunch" -> if (dark) Color(0xFF28402A) else Color(0xFFC8E6C9)
            "dinner" -> if (dark) Color(0xFF352D4A) else Color(0xFFD1C4E9)
            else -> if (dark) Color(0xFF4A2E24) else Color(0xFFFFCCBC)
        }
    }
}