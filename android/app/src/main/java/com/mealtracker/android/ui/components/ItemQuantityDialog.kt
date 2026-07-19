package com.mealtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mealtracker.android.network.models.Item
import kotlin.math.roundToInt

/**
 * Quantity/serving picker for contexts that have an Item but no meal or
 * day to compare against -- currently just recipe ingredient picking
 * (see design discussion: "the more you can reuse from the main search,
 * the better", building this alongside CreateRecipeIngredientsScreen).
 *
 * Deliberately NOT a clone of MealDetailScreen's ItemLogPageDialog --
 * that one also handles photo editing and "share of this meal's goal"
 * progress bars, neither of which make sense for a not-yet-logged
 * recipe ingredient with no meal/day of its own. What IS mirrored
 * exactly is the actual quantity semantics: unit is either raw grams or
 * one of the item's named ServingSizes, and with a serving selected,
 * the quantity typed is a MULTIPLIER of that serving's weight (2 x
 * "slice" @ 37.5g = 75g) -- matches LoggableEntryBase's semantics on
 * the backend, same as everywhere else quantity gets picked in this app.
 *
 * "+ Create new serving" reuses the shared CreateServingDialog, same as
 * the meal-logging picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemQuantityDialog(
    item: Item,
    quantityInput: String,
    servingSizeId: Int?,
    isSaving: Boolean,
    error: String?,
    confirmLabel: String,
    onQuantityChange: (String) -> Unit,
    onServingChange: (Int?) -> Unit,
    onCreateNewServing: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // Non-null only when editing an ingredient/log that already exists
    // (as opposed to adding a new one) -- renders a small red "Remove"
    // text below Save, same "smaller tappable text" convention used
    // elsewhere in the app (e.g. deleting a recipe from its info
    // screen), rather than a separate delete icon/button.
    onRemove: (() -> Unit)? = null
) {
    var unitMenuExpanded by remember { mutableStateOf(false) }
    val selectedServing = item.servingSizes.find { it.id == servingSizeId }
    val unitLabel = selectedServing?.name ?: "g"

    val quantityValue = quantityInput.toDoubleOrNull()
    val effectiveGrams = when {
        quantityValue == null -> 0.0
        selectedServing != null -> quantityValue * (selectedServing.weightG.toDoubleOrNull() ?: 0.0)
        else -> quantityValue
    }
    val kcal = ((item.kcal100g?.toDoubleOrNull() ?: 0.0) * effectiveGrams / 100.0).roundToInt()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CatalogVisuals.backgroundFor(item.type)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.imagePath != null) {
                            coil3.compose.AsyncImage(
                                model = com.mealtracker.android.BuildConfig.BASE_URL + item.imagePath,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                CatalogVisuals.iconFor(item.type),
                                contentDescription = null,
                                tint = CatalogVisuals.iconTint(),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        if (item.brand != null) {
                            Text(
                                item.brand,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = onQuantityChange,
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))

                    Box {
                        AssistChip(
                            onClick = { unitMenuExpanded = true },
                            label = { Text(unitLabel) }
                        )
                        DropdownMenu(expanded = unitMenuExpanded, onDismissRequest = { unitMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("g") },
                                onClick = { onServingChange(null); unitMenuExpanded = false }
                            )
                            item.servingSizes.forEach { serving ->
                                DropdownMenuItem(
                                    text = { Text("${serving.name} (${serving.weightG}g)") },
                                    onClick = { onServingChange(serving.id); unitMenuExpanded = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Create new serving") },
                                onClick = { unitMenuExpanded = false; onCreateNewServing() }
                            )
                        }
                    }
                }

                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                Text(
                    "$kcal Cal for ${effectiveGrams.roundToInt()}g",
                    style = MaterialTheme.typography.titleMedium
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

                Button(onClick = onConfirm, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isSaving) "Saving..." else confirmLabel)
                }

                if (onRemove != null) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                    androidx.compose.material3.TextButton(onClick = onRemove, enabled = !isSaving) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}