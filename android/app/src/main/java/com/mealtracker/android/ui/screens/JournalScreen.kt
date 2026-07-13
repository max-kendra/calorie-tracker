package com.mealtracker.android.ui.screens

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

/**
 * Journal screen -- step 1 of building this out: just the meal cards
 * with REAL data (no header rings, no date nav yet -- those come next,
 * once this is confirmed working). Always shows today's date for now;
 * date navigation is a later step.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = viewModel(),
    onNavigateToAddItem: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    Button(
                        onClick = onNavigateToAddItem,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add Item")
                    }
                }
                items(state.buckets) { bucket ->
                    MealCard(bucket)
                }
            }
        }
    }
}

@Composable
private fun MealCard(bucket: MealBucket) {
    Card(modifier = Modifier.fillMaxWidth()) {
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