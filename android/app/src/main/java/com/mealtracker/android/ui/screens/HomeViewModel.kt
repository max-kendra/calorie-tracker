package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Minimal example of the pattern we'll reuse for every screen that talks
 * to the API: a sealed class describing the possible UI states, a
 * StateFlow the Composable observes, and a coroutine (via
 * viewModelScope.launch) that calls the suspend function on ApiService
 * and updates the state based on what happens.
 *
 * This is intentionally simple -- no repository layer, no dependency
 * injection framework. Fine for a skeleton; introduce more structure
 * later if/when the app's complexity actually calls for it.
 */
sealed class HealthUiState {
    object Loading : HealthUiState()
    data class Success(val status: String) : HealthUiState()
    data class Error(val message: String) : HealthUiState()
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HealthUiState>(HealthUiState.Loading)
    val uiState: StateFlow<HealthUiState> = _uiState

    init {
        checkHealth()
    }

    fun checkHealth() {
        _uiState.value = HealthUiState.Loading
        viewModelScope.launch {
            try {
                val response = ApiClient.service.getHealth()
                _uiState.value = HealthUiState.Success(response.status)
            } catch (e: Exception) {
                // Common causes if this fails: BASE_URL in build.gradle.kts
                // doesn't match your Pi's actual Tailscale address, your
                // phone isn't connected to Tailscale, or the API container
                // isn't running on the Pi.
                _uiState.value = HealthUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
