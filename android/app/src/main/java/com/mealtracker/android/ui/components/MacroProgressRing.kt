package com.mealtracker.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A single small progress ring for one macro - eaten/goal shown in the
 * center, colored per macro. Reused on the Journal screen's daily
 * summary panel and the Meal Detail screen's per-meal breakdown, so the
 * two screens look visually consistent (same rings, different scope of
 * data feeding them).
 */
@Composable
fun MacroProgressRing(
    label: String,
    eaten: Int,
    goal: Int,
    color: Color,
    diameter: Dp = 72.dp,
    strokeWidth: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val fraction = if (goal > 0) (eaten.toFloat() / goal.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DonutChart(
            segments = listOf(fraction to color),
            diameter = diameter,
            strokeWidth = strokeWidth,
            // Faded version of the SAME color, not a generic gray - the
            // unfilled track is a pale tint of the macro's own color, so
            // it visually "fills up" with a more vibrant version of
            // itself as eaten approaches goal, rather than the ring
            // looking like a different color underneath.
            trackColor = color.copy(alpha = 0.22f),
            centerContent = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$eaten", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "/${goal}g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Row of four macro rings, in order: Fat, Carbs, Fiber, Protein. */
@Composable
fun MacroRingsRow(
    fatEaten: Int, fatGoal: Int,
    proteinEaten: Int, proteinGoal: Int,
    carbsEaten: Int, carbsGoal: Int,
    fiberEaten: Int, fiberGoal: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
    ) {
        MacroProgressRing("Fat", fatEaten, fatGoal, MacroColors.Fat)
        MacroProgressRing("Carbs", carbsEaten, carbsGoal, MacroColors.Carbs)
        MacroProgressRing("Fiber", fiberEaten, fiberGoal, MacroColors.Fiber)
        MacroProgressRing("Protein", proteinEaten, proteinGoal, MacroColors.Protein)
    }
}

/** Single source of truth for macro colors - matches the Macronutrients
 * screen's palette, reused here so rings look consistent app-wide. */
object MacroColors {
    val Fat = Color(0xFFE6B800)
    val Protein = Color(0xFFE8837A)
    val Carbs = Color(0xFF7EC8E3)
    val Fiber = Color(0xFF9C7A54)
}