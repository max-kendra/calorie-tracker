package com.mealtracker.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Reached from a quantity/serving picker's unit dropdown -- backend
 * already had full CRUD for this (POST/PATCH/DELETE
 * /items/{id}/serving-sizes), just needed a client UI to reach it.
 * Shared across every place that offers "+ Create new serving" (meal
 * logging, recipe ingredient picking) rather than each maintaining its
 * own copy. */
@Composable
fun CreateServingDialog(
    name: String,
    weightG: String,
    isCreating: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New serving") },
        text = {
            val focusManager = LocalFocusManager.current
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name (e.g. \"slice\")") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = weightG,
                    onValueChange = onWeightChange,
                    label = { Text("Weight (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isCreating) {
                Text(if (isCreating) "Creating..." else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}