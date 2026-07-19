package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun WeightGoalScreen(
    viewModel: WeightGoalViewModel = viewModel(),
    onBack: () -> Unit,
    onSaved: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) onSaved?.invoke()
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Weight goal", style = MaterialTheme.typography.headlineSmall)
        }

        if (state.loadError != null) {
            Text(
                state.loadError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Text(
            "Your current weight is read from Health Connect automatically - these two " +
                "values are just fixed reference points for the progress summary on your " +
                "profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = state.startingWeightKg,
            onValueChange = viewModel::updateStartingWeightKg,
            label = { Text("Starting weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        OutlinedTextField(
            value = state.goalWeightKg,
            onValueChange = viewModel::updateGoalWeightKg,
            label = { Text("Goal weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        Button(
            onClick = { viewModel.save() },
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isSaving) "Saving..." else "Save")
        }
        if (state.saveError != null) {
            Text(
                "Couldn't save: ${state.saveError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.saveSuccess) {
            Text("\u2705 Saved", color = MaterialTheme.colorScheme.primary)
        }
    }
}