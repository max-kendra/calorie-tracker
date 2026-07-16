package com.mealtracker.android.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.ui.components.CalendarPickerDialog
import com.mealtracker.android.ui.components.DonutChart
import com.mealtracker.android.ui.components.MacroRingsRow
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Journal screen -- two cards on a teal background (see ui/theme/Color.kt):
 * a collapsible macro summary card (kcal ring + 4 macro rings) and a
 * scrollable calendar card (date navigation + that day's meal cards).
 * Deliberately no calories-burned tracking or food-rating/"daily
 * assessment" feature -- both out of scope, see design doc.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = viewModel(),
    onNavigateToMealDetail: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val calendarMonthState by viewModel.calendarState.collectAsState()
    var macroCardExpanded by remember { mutableStateOf(true) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var pickerMonth by remember { mutableStateOf(YearMonth.now()) }

    // Bottom-nav tab switches preserve ViewModel state by design (see
    // AppNavHost's saveState/restoreState config) -- but Journal should
    // ALWAYS show today when you tap the tab, never "wherever you last
    // left off" once date navigation exists. Re-running this on every
    // entry into the screen (not just first composition) overrides that
    // preservation specifically for the date, without needing to opt the
    // whole ViewModel out of state-saving.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadJournal(LocalDate.now())
    }

    when (val state = uiState) {
        is JournalUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is JournalUiState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Couldn't load your journal", style = MaterialTheme.typography.titleMedium)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { viewModel.loadJournal(LocalDate.now()) }) {
                    Text("Retry")
                }
            }
        }
        is JournalUiState.Success -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DailySummaryCard(
                    totals = state.dailyTotals,
                    expanded = macroCardExpanded,
                    onToggleExpanded = { macroCardExpanded = !macroCardExpanded }
                )
                CalendarCard(
                    date = state.date,
                    buckets = state.buckets,
                    onPrevDay = { viewModel.loadJournal(state.date.minusDays(1)) },
                    onNextDay = { viewModel.loadJournal(state.date.plusDays(1)) },
                    onDateClick = {
                        pickerMonth = YearMonth.from(state.date)
                        viewModel.loadCalendarMonth(pickerMonth)
                        showCalendarPicker = true
                    },
                    onMealClick = onNavigateToMealDetail,
                    modifier = Modifier.weight(1f)
                )
            }

            if (showCalendarPicker) {
                CalendarPickerDialog(
                    displayedMonth = pickerMonth,
                    selectedDate = state.date,
                    monthState = calendarMonthState,
                    onMonthChange = { newMonth ->
                        pickerMonth = newMonth
                        viewModel.loadCalendarMonth(newMonth)
                    },
                    onDateSelected = { date ->
                        viewModel.loadJournal(date)
                        showCalendarPicker = false
                        viewModel.clearCalendarState()
                    },
                    onDismiss = {
                        showCalendarPicker = false
                        viewModel.clearCalendarState()
                    }
                )
            }
        }
    }
}

@Composable
private fun DailySummaryCard(
    totals: DailyTotals,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val remaining = totals.goalKcal - totals.eatenKcal

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Macronutrients", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (!expanded) {
                // Compact one-line summary when collapsed -- enough to
                // glance at without needing to expand, matching the
                // purpose of a collapsible card (hide detail, not hide
                // the headline number).
                Text(
                    if (remaining >= 0) "$remaining Cal left \u00b7 ${totals.eatenKcal} eaten"
                    else "${-remaining} Cal over \u00b7 ${totals.eatenKcal} eaten",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (remaining >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                return@Column
            }

            val kcalFraction = if (totals.goalKcal > 0) {
                (totals.eatenKcal.toFloat() / totals.goalKcal.toFloat()).coerceIn(0f, 1f)
            } else 0f

            DonutChart(
                segments = listOf(kcalFraction to MaterialTheme.colorScheme.primary),
                diameter = 140.dp,
                strokeWidth = 14.dp,
                // Faded version of the same primary color, not a generic
                // gray -- matches the macro rings below (see
                // MacroProgressRing) so the whole card reads as
                // "pale -> vibrant" of ONE color per ring, consistently.
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                centerContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (remaining >= 0) {
                            Text("$remaining", style = MaterialTheme.typography.headlineMedium)
                            Text("Cal left", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "${-remaining}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Cal over",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
            Text(
                "${totals.eatenKcal} eaten \u00b7 ${totals.goalKcal} goal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

            MacroRingsRow(
                fatEaten = totals.eatenFat, fatGoal = totals.goalFat,
                proteinEaten = totals.eatenProtein, proteinGoal = totals.goalProtein,
                carbsEaten = totals.eatenCarbs, carbsGoal = totals.goalCarbs,
                fiberEaten = totals.eatenFiber, fiberGoal = totals.goalFiber,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CalendarCard(
    date: LocalDate,
    buckets: List<MealBucket>,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit,
    onMealClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevDay) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Text(
                    date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(onClick = onDateClick)
                )
                IconButton(onClick = onNextDay) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // The card itself doesn't scroll (it's sized by the parent
            // Column's weight(1f) in JournalScreen) -- this LazyColumn is
            // what actually scrolls, independently of the macro card
            // above it, once the meal list is taller than the available
            // space.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(buckets) { bucket ->
                    MealCard(bucket, onClick = { onMealClick(bucket.mealType) })
                }
            }
        }
    }
}

@Composable
private fun MealCard(bucket: MealBucket, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${bucket.displayName} \u00b7 ${bucket.eatenKcal} / ${bucket.goalKcal} Cal",
                style = MaterialTheme.typography.titleMedium
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

            if (bucket.logs.isEmpty()) {
                Text(
                    text = "Nothing logged yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = describeItems(bucket.logs),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Comma-separated "Name (X Cal), Name (Y Cal), ..." -- matches the
 * design doc's Journal meal card text format. Uses itemName if it's an
 * item-based log, recipeName if recipe-based (exactly one is non-null,
 * matching the backend's CHECK constraint).
 */
private fun describeItems(logs: List<Log>): String {
    return logs.joinToString(", ") { log ->
        val name = log.itemName ?: log.recipeName ?: "Unknown"
        "$name (${log.kcalLogged} Cal)"
    }
}