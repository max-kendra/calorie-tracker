package com.mealtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.ui.components.CreateServingDialog
import com.mealtracker.android.ui.components.ItemQuantityDialog
import com.mealtracker.android.ui.components.ItemResultsList

/**
 * Entry point for the meal-detail add sheet's "Create" method -- just
 * dispatches between the two phases (see CreateRecipeViewModel.
 * CreateRecipePhase). Kept as its own file/composable so
 * MealDetailScreen's CREATE branch only has to know about one entry
 * point, same shape as AddItemScreen's single entry point for the
 * Barcode branch.
 */
@Composable
fun CreateRecipeContent(
    viewModel: CreateRecipeViewModel = viewModel(),
    lastLoggedAmounts: Map<Int, LoggedAmount>,
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

    when (state.phase) {
        CreateRecipePhase.DETAILS -> CreateRecipeDetailsScreen(viewModel = viewModel, onDone = onDone)
        CreateRecipePhase.INGREDIENTS -> CreateRecipeIngredientsScreen(
            viewModel = viewModel,
            lastLoggedAmounts = lastLoggedAmounts,
            onDone = onDone
        )
    }
}

/**
 * First step: just the recipe's own metadata (name, servings) -- split
 * out from ingredient-picking per design discussion ("name the recipe
 * and give the amount of servings on a separate screen"), so the
 * ingredients screen underneath can solely focus on searching/scanning
 * without also juggling a name field at the top.
 */
@Composable
private fun CreateRecipeDetailsScreen(viewModel: CreateRecipeViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Create a recipe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Recipe name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        OutlinedTextField(
            value = state.servings,
            onValueChange = viewModel::updateServings,
            label = { Text("Servings") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

        Button(
            onClick = { viewModel.proceedToIngredients() },
            enabled = state.isDetailsValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Next: add ingredients")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}

/**
 * Second step: search or scan barcodes to add ingredients -- reuses as
 * much of the main meal-add flow as the different context allows (see
 * design discussion: "the more you can reuse from the main search, the
 * better"). Concretely reused, not just visually similar:
 *  - ItemResultsList (images, brand, last-used-quantity preview) --
 *    the exact same component the main meal search uses.
 *  - ItemQuantityDialog for tap-to-adjust-quantity -- same quantity/
 *    serving semantics as the meal-logging picker, including
 *    "+ Create new serving" via the shared CreateServingDialog.
 *  - AddItemScreen/AddItemViewModel for barcode scanning -- the entire
 *    scan -> match/create -> confirm flow, just pointed at
 *    "add as an ingredient" (via onUseCreatedItem) instead of
 *    "log to a meal".
 * No third "Create" toggle here -- Create is what got you to this
 * screen in the first place, so only Search/Barcode make sense as
 * methods underneath it.
 */
@Composable
private fun CreateRecipeIngredientsScreen(
    viewModel: CreateRecipeViewModel,
    lastLoggedAmounts: Map<Int, LoggedAmount>,
    onDone: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.backToDetails() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to recipe details")
            }
            Text(state.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Cancel") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IngredientMethodChip(
                icon = Icons.Filled.Search,
                label = "Search",
                selected = state.ingredientMode == CreateRecipeIngredientMode.SEARCH,
                onClick = { viewModel.selectIngredientMode(CreateRecipeIngredientMode.SEARCH) }
            )
            IngredientMethodChip(
                icon = Icons.Filled.QrCodeScanner,
                label = "Barcode",
                selected = state.ingredientMode == CreateRecipeIngredientMode.BARCODE,
                onClick = { viewModel.selectIngredientMode(CreateRecipeIngredientMode.BARCODE) }
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        if (state.ingredients.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("Added so far", style = MaterialTheme.typography.titleSmall)
                state.ingredients.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${row.item.name} \u00b7 ${"%.0f".format(row.quantityG)}g",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.removeIngredient(row.item.itemId) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${row.item.name}")
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = { viewModel.save() },
                    enabled = state.isSaveValid && !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isSaving) "Saving..." else "Save recipe")
                }
                if (state.saveError != null) {
                    Text(
                        "Couldn't save: ${state.saveError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
            }
        }

        when (state.ingredientMode) {
            CreateRecipeIngredientMode.SEARCH -> {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = state.ingredientSearchQuery,
                        onValueChange = viewModel::updateIngredientSearchQuery,
                        label = { Text("Search for an ingredient") },
                        singleLine = true,
                        trailingIcon = if (state.ingredientSearchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.updateIngredientSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ItemResultsList(
                    items = state.ingredientSearchResults,
                    isLoading = state.isSearchingIngredients,
                    emptyMessage = if (state.ingredientSearchQuery.isBlank()) {
                        "Search for an item to add as an ingredient."
                    } else {
                        "No matches."
                    },
                    quickLoggingItemId = null,
                    lastLoggedAmounts = lastLoggedAmounts,
                    onItemClick = { viewModel.openQuantityPicker(it, lastLoggedAmounts) },
                    onQuickAddClick = { itemId ->
                        state.ingredientSearchResults.find { it.itemId == itemId }?.let { viewModel.openQuantityPicker(it, lastLoggedAmounts) }
                    }
                )
            }
            CreateRecipeIngredientMode.BARCODE -> {
                val addItemViewModel: AddItemViewModel =
                    viewModel(key = "create_recipe_barcode_${System.identityHashCode(viewModel)}")
                AddItemScreen(
                    viewModel = addItemViewModel,
                    savedScreenPromptText = "Want to review it before adding to your recipe?",
                    savedScreenActionLabel = "View Ingredient",
                    onUseCreatedItem = { item ->
                        addItemViewModel.resetToScanChoice()
                        viewModel.selectIngredientMode(CreateRecipeIngredientMode.SEARCH)
                        viewModel.addIngredientFromBarcodeFlow(item)
                    },
                    onBack = {
                        addItemViewModel.resetToScanChoice()
                        viewModel.selectIngredientMode(CreateRecipeIngredientMode.SEARCH)
                    },
                    onDone = {
                        addItemViewModel.resetToScanChoice()
                        viewModel.selectIngredientMode(CreateRecipeIngredientMode.SEARCH)
                    }
                )
            }
        }
    }

    if (state.itemForQuantityPicker != null) {
        ItemQuantityDialog(
            item = state.itemForQuantityPicker!!,
            quantityInput = state.quantityPickerInput,
            servingSizeId = state.quantityPickerServingSizeId,
            isSaving = false,
            error = null,
            confirmLabel = "Add ingredient",
            onQuantityChange = viewModel::updateQuantityPickerInput,
            onServingChange = viewModel::updateQuantityPickerServing,
            onCreateNewServing = { viewModel.openCreateServingDialog() },
            onConfirm = { viewModel.confirmQuantityPicker() },
            onDismiss = { viewModel.dismissQuantityPicker() }
        )
    }

    if (state.showCreateServingDialog) {
        CreateServingDialog(
            name = state.newServingName,
            weightG = state.newServingWeightG,
            isCreating = state.isCreatingServing,
            error = state.createServingError,
            onNameChange = viewModel::updateNewServingName,
            onWeightChange = viewModel::updateNewServingWeightG,
            onConfirm = { viewModel.createServing() },
            onDismiss = { viewModel.dismissCreateServingDialog() }
        )
    }
}

@Composable
private fun IngredientMethodChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .padding(4.dp)
                .background(background, androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = iconTint)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
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