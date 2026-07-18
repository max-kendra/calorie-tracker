package com.mealtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.ui.components.MacroColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CARD_CORNER_RADIUS = 24.dp
private val KcalColor = Color(0xFF2C6E63) // TealPrimary -- kcal isn't a "macro" per se, gets the app's accent color instead of a MacroColors entry

/**
 * Home screen. Layout, top to bottom:
 *   - greeting (time-of-day dependent text; the illustrated graphic that
 *     changes with it is a later addition, see design discussion)
 *   - streak card
 *   - weekly progress bars (kcal + 4 macros) -- the "at a glance" view
 *   - weekly day-by-day table -- the "why does that bar look like that"
 *     detail view, directly underneath
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GreetingHeader()

        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is HomeUiState.Error -> {
                Card(
                    shape = RoundedCornerShape(CARD_CORNER_RADIUS),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't load this week", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { viewModel.load() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            is HomeUiState.Success -> {
                StreakCard(streakDays = state.streakDays)
                WeeklyProgressCard(weekly = state.weekly)
                WeeklyTableCard(weekly = state.weekly)
                ThresholdsCard(thresholds = state.thresholds)
            }
        }
    }
}

@Composable
private fun GreetingHeader() {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Still up?"
    }
    Text(greeting, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StreakCard(streakDays: Int) {
    Card(
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Placeholder icon -- swap for the Lottie flame-with-a-face
            // animation once you've picked one out.
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFE8837A),
                modifier = Modifier.height(36.dp).width(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    if (streakDays == 1) "1 day streak" else "$streakDays day streak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (streakDays == 0) "Log something today to start one" else "Keep it going",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeeklyProgressCard(weekly: WeeklySummary) {
    Card(
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "This week",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                weekRangeLabel(weekly.weekStart, weekly.weekEnd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            WeeklyMacroBar("Calories", weekly.eaten.kcal, weekly.goal.kcal, KcalColor, unit = "kcal")
            WeeklyMacroBar("Protein", weekly.eaten.protein, weekly.goal.protein, MacroColors.Protein)
            WeeklyMacroBar("Fat", weekly.eaten.fat, weekly.goal.fat, MacroColors.Fat)
            WeeklyMacroBar("Carbs", weekly.eaten.carbs, weekly.goal.carbs, MacroColors.Carbs)
            WeeklyMacroBar("Fiber", weekly.eaten.fiber, weekly.goal.fiber, MacroColors.Fiber)
        }
    }
}

/** One labeled bar: fills up to 100% of the weekly goal, in the macro's
 * own color; going over the goal is called out with a small "+Xg over"
 * label in the same color rather than the bar just clipping silently,
 * since going over is exactly the thing you want to notice at a glance. */
@Composable
private fun WeeklyMacroBar(label: String, eaten: Int, goal: Int, color: Color, unit: String = "g") {
    val fraction = if (goal > 0) (eaten.toFloat() / goal.toFloat()).coerceIn(0f, 1f) else 0f
    val over = eaten - goal

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "$eaten / $goal $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
            )
        }
        if (over > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "+$over $unit over",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
private fun WeeklyTableCard(weekly: WeeklySummary) {
    Card(
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Day by day",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                // Row-label column, pinned to the visual left by simply
                // being drawn first (outside the horizontal scroll would
                // need a two-pane layout -- fine to keep simple for a
                // 7-day table that mostly fits without scrolling on a
                // normal phone width anyway).
                Column {
                    TableHeaderCell("")
                    TableCell("Calories")
                    TableCell("Protein")
                    TableCell("Fat")
                    TableCell("Carbs")
                    TableCell("Fiber")
                }
                weekly.days.forEach { day ->
                    Column {
                        TableHeaderCell(
                            dayLabel(day),
                            emphasized = day.isToday
                        )
                        TableValueCell(day.totals.kcal, day.isFuture)
                        TableValueCell(day.totals.protein, day.isFuture)
                        TableValueCell(day.totals.fat, day.isFuture)
                        TableValueCell(day.totals.carbs, day.isFuture)
                        TableValueCell(day.totals.fiber, day.isFuture)
                    }
                }
            }
        }
    }
}

/** "Don't go over" nutrients -- sodium, added sugar, saturated fat.
 * Unlike the macro bars above, there's no good reason to chase these
 * upward, so the framing is just "how close to the weekly ceiling am I,
 * and what pushed me there" rather than "did I hit my target". */
@Composable
private fun ThresholdsCard(thresholds: ThresholdsSummary) {
    Card(
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Keep an eye on",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Weekly upper limits — not targets to hit, just ones not to cross",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ThresholdRow(thresholds.sodium)
            ThresholdRow(thresholds.addedSugar)
            ThresholdRow(thresholds.saturatedFat)
        }
    }
}

private val ThresholdNormalColor = Color(0xFFAD8B3D) // muted amber -- distinct from the macro palette, reads as "caution ceiling" rather than "progress towards a goal"

@Composable
private fun ThresholdRow(nutrient: ThresholdNutrient) {
    val fraction = if (nutrient.maxWeekly > 0) (nutrient.eatenWeekly.toFloat() / nutrient.maxWeekly.toFloat()).coerceIn(0f, 1f) else 0f
    val isOver = nutrient.maxWeekly > 0 && nutrient.eatenWeekly > nutrient.maxWeekly
    val color = if (isOver) MaterialTheme.colorScheme.error else ThresholdNormalColor

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(nutrient.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${nutrient.eatenWeekly} / ${nutrient.maxWeekly} ${nutrient.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
            )
        }
        if (isOver) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "+${nutrient.eatenWeekly - nutrient.maxWeekly} ${nutrient.unit} over",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        if (nutrient.topContributors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                nutrient.topContributors.forEachIndexed { index, contribution ->
                    Text(
                        "${index + 1}. ${contribution.name} — ${contribution.amount}${nutrient.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private const val TABLE_CELL_WIDTH_DP = 56

@Composable
private fun TableHeaderCell(text: String, emphasized: Boolean = false) {
    Box(
        modifier = Modifier.width(TABLE_CELL_WIDTH_DP.dp).height(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TableCell(text: String) {
    Box(
        modifier = Modifier.width(88.dp).height(32.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableValueCell(value: Int, isFuture: Boolean) {
    Box(
        modifier = Modifier.width(TABLE_CELL_WIDTH_DP.dp).height(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            // Pretracked/future days shown de-emphasized -- they're
            // part of the plan, not something that's actually happened
            // yet, and the muted color reflects that distinction.
            color = if (isFuture) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun dayLabel(day: DaySummary): String =
    day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))

private fun weekRangeLabel(start: LocalDate, end: LocalDate): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return "${start.format(formatter)} – ${end.format(formatter)}"
}