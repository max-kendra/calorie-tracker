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
    // Same "darker arc painted over the already-full ring" treatment as
    // JournalScreen's kcal hero ring, applied here too so every macro
    // ring - not just the kcal one - shows how far over goal you are
    // instead of just silently capping at a full ring (see design
    // discussion: "can we do this for the macro wheels everywhere").
    val overflowFraction = if (goal > 0 && eaten > goal) {
        ((eaten - goal).toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    } else 0f

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
            overlaySegments = listOf(overflowFraction to darkenMacroColor(color)),
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

/** Row of four macro rings, in order: Protein, Fat, Carbs, Fiber. */
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
        MacroProgressRing("Protein", proteinEaten, proteinGoal, MacroColors.Protein)
        MacroProgressRing("Fat", fatEaten, fatGoal, MacroColors.Fat)
        MacroProgressRing("Carbs", carbsEaten, carbsGoal, MacroColors.Carbs)
        MacroProgressRing("Fiber", fiberEaten, fiberGoal, MacroColors.Fiber)
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

/** A darker shade of the same macro color, for the "how far over goal"
 * overlay on rings/bars - mixes toward black rather than switching to a
 * different hue (e.g. error red), so going over reads as "more of the
 * same macro" rather than a separate warning color unrelated to what's
 * actually being shown. Shared between MacroProgressRing (rings) and
 * HomeScreen's WeeklyMacroBar (bars) so the "over" treatment looks the
 * same wherever a macro goal appears, wheel or bar. */
fun darkenMacroColor(color: Color, factor: Float = 0.35f): Color = Color(
    red = color.red * (1f - factor),
    green = color.green * (1f - factor),
    blue = color.blue * (1f - factor),
    alpha = color.alpha
)