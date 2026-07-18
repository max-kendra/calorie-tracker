package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Recipe

/**
 * Content for the meal-detail add sheet's "Create" method -- builds a
 * brand-new recipe (name, servings, a searched-and-added ingredient
 * list with per-ingredient gram quantities), then offers to either log
 * it straight to the meal that was open, or just finish and leave it
 * saved for later. See CreateRecipeViewModel for the actual save logic
 * and MealDetailScreen's CREATE branch for how this gets embedded.
 */
@Composable
fun CreateRecipeContent(
    viewModel: CreateRecipeViewModel = viewModel(),
    onLogToMeal: (Recipe) -> Unit,
    onDone: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    if (state.createdRecipe != null) {
        RecipeCreatedContent(
            recipe = state.createdRecipe!!,
            onLogToMeal = { onLogToMeal(state.createdRecipe!!) },
            onDone = onDone
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text("Create a recipe", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Recipe name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        OutlinedTextField(
            value = state.servings,
            onValueChange = viewModel::updateServings,
            label = { Text("Servings") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

        Text("Ingredients", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))

        if (state.ingredients.isEmpty()) {
            Text(
                "No ingredients added yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.ingredients.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = row.quantityG,
                        onValueChange = { viewModel.updateIngredientQuantity(row.item.itemId, it) },
                        label = { Text("g") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.padding(start = 8.dp).width(90.dp)
                    )
                    IconButton(onClick = { viewModel.removeIngredient(row.item.itemId) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${row.item.name}")
                    }
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        OutlinedTextField(
            value = state.ingredientSearchQuery,
            onValueChange = viewModel::updateIngredientSearchQuery,
            label = { Text("Add an ingredient") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.isSearchingIngredients) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
        } else {
            state.ingredientSearchResults.forEach { item: Item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        if (item.brand != null) {
                            Text(item.brand, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { viewModel.addIngredient(item) }) {
                        Text("Add")
                    }
                }
                HorizontalDivider()
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

        Button(
            onClick = { viewModel.save() },
            enabled = state.isValid && !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isSaving) "Saving..." else "Save recipe")
        }

        if (state.saveError != null) {
            Text(
                "Couldn't save: ${state.saveError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun RecipeCreatedContent(recipe: Recipe, onLogToMeal: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("\u2705 \"${recipe.name}\" saved", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onLogToMeal, modifier = Modifier.fillMaxWidth()) {
            Text("Log to this meal")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = onDone) {
            Text("Done")
        }
    }
}