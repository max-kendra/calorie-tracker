package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

/**
 * Placeholder Home screen for the skeleton -- just proves the whole
 * chain works: Compose UI -> ViewModel -> Retrofit -> your Pi's API ->
 * back again. Replace this with the real Home screen design (weekly
 * summaries, etc. from the design doc) once this round-trip is confirmed
 * working on your device.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Meal Tracker",
            style = MaterialTheme.typography.headlineMedium
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))

        when (val state = uiState) {
            is HealthUiState.Loading -> {
                CircularProgressIndicator()
                Text("Connecting to your API...")
            }
            is HealthUiState.Success -> {
                Text(
                    text = "✅ Connected — API status: ${state.status}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            is HealthUiState.Error -> {
                Text(
                    text = "❌ Couldn't reach the API",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(onClick = { viewModel.checkHealth() }) {
                    Text("Retry")
                }
            }
        }
    }
}
