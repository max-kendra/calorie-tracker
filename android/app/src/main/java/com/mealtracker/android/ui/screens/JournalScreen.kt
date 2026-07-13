package com.mealtracker.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.ui.components.DonutChart
import com.mealtracker.android.ui.components.MacroRingsRow

/**
 * Journal screen -- daily summary panel (big kcal ring + 4 macro rings,
 * matching the design doc's mockup but WITHOUT calories-burned tracking
 * or any food-rating/"daily assessment" feature -- both deliberately out
 * of scope, see design doc) followed by the four meal cards. Tapping any
 * card (even an empty one) opens that meal's detail screen.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = viewModel(),
    onNavigateToMealDetail: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Bottom-nav tab switches preserve ViewModel state by design (see
    // AppNavHost's saveState/restoreState config) -- but Journal should
    // ALWAYS show today when you tap the tab, never "wherever you last
    // left off" once date navigation exists. Re-running this on every
    // entry into the screen (not just first composition) overrides that
    // preservation specifically for the date, without needing to opt the
    // whole ViewModel out of state-saving.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadJournal(java.time.LocalDate.now())
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
                Button(onClick = { viewModel.loadJournal(java.time.LocalDate.now()) }) {
                    Text("Retry")
                }
            }
        }
        is JournalUiState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    DailySummaryPanel(state.dailyTotals)
                }
                items(state.buckets) { bucket ->
                    MealCard(bucket, onClick = { onNavigateToMealDetail(bucket.mealType) })
                }
            }
        }
    }
}

@Composable
private fun DailySummaryPanel(totals: DailyTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val remaining = totals.goalKcal - totals.eatenKcal
            val kcalFraction = if (totals.goalKcal > 0) {
                (totals.eatenKcal.toFloat() / totals.goalKcal.toFloat()).coerceIn(0f, 1f)
            } else 0f

            DonutChart(
                segments = listOf(kcalFraction to MaterialTheme.colorScheme.primary),
                diameter = 140.dp,
                strokeWidth = 14.dp,
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
private fun MealCard(bucket: MealBucket, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
