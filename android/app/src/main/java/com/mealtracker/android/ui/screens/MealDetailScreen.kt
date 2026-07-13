package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.ui.components.MacroRingsRow
import java.time.LocalDate

/**
 * Opens for ANY meal card tap, even an empty one (per design doc) --
 * meal name centered above the macro info (not sharing space with the
 * back button in a title bar), a kcal progress bar for that meal's
 * slice of the daily goal, and the same 4-ring macro breakdown used on
 * the Journal screen, scoped to just this meal. Star icon next to the
 * name saves the current items as a reusable named Meal (a Recipe with
 * recipe_type="meal", see design doc). Add Item entry point lives in
 * the fixed bottom bar, since adding is always in the context of a
 * specific meal.
 */
@Composable
fun MealDetailScreen(
    date: LocalDate,
    mealType: String,
    viewModel: MealDetailViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNavigateToAddItem: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(date, mealType) {
        viewModel.load(date, mealType)
    }

    if (state.showSaveMealDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveMealDialog() },
            title = { Text("Save this meal") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.mealNameInput,
                        onValueChange = { viewModel.updateMealNameInput(it) },
                        label = { Text("Meal name") }
                    )
                    if (state.saveMealError != null) {
                        Text(
                            state.saveMealError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveAsMeal() },
                    enabled = state.mealNameInput.isNotBlank() && !state.isSavingMeal
                ) {
                    Text(if (state.isSavingMeal) "Saving..." else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSaveMealDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        bottomBar = {
            // Only "Barcode" is wired up for now -- Search/My Foods will
            // join once the log-an-existing-item flow is built; not
            // adding placeholder buttons that don't do anything yet.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                IconButton(onClick = onNavigateToAddItem) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Add item via barcode")
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't load this meal", style = MaterialTheme.typography.titleMedium)
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Meal name, centered, with the star/bookmark icon
                    // right next to it -- deliberately separate from the
                    // top bar's back button, per design doc.
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(state.displayName, style = MaterialTheme.typography.headlineSmall)
                            IconButton(onClick = { viewModel.openSaveMealDialog() }) {
                                Icon(Icons.Filled.BookmarkBorder, contentDescription = "Save as a meal")
                            }
                        }
                    }
                    if (state.saveMealSuccess) {
                        Text(
                            "\u2705 Saved as a reusable meal",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

                    val kcalFraction = if (state.goalKcal > 0) {
                        (state.eatenKcal.toFloat() / state.goalKcal.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Text(
                        "${state.eatenKcal} / ${state.goalKcal} Cal",
                        style = MaterialTheme.typography.titleMedium
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                    LinearProgressIndicator(
                        progress = { kcalFraction },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

                    MacroRingsRow(
                        fatEaten = state.eatenFat, fatGoal = state.goalFat,
                        proteinEaten = state.eatenProtein, proteinGoal = state.goalProtein,
                        carbsEaten = state.eatenCarbs, carbsGoal = state.goalCarbs,
                        fiberEaten = state.eatenFiber, fiberGoal = state.goalFiber,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    if (state.logs.isEmpty()) {
                        Text(
                            "Nothing logged yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.logs.forEach { log -> LogRow(log) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: Log) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(log.itemName ?: log.recipeName ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
        Text("${log.kcalLogged} Cal", style = MaterialTheme.typography.bodyLarge)
    }
}
