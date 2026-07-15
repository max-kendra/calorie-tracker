package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.UserProfileUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class WeightGoalUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val startingWeightKg: String = "",
    val goalWeightKg: String = "",
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false
)

/**
 * Purely the two fixed reference points shown in the Profile screen's
 * weight-goal summary -- see UserProfile model docstring (backend) for
 * why these are stored values, unlike "current weight" which always
 * comes live from Health Connect.
 */
class WeightGoalViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WeightGoalUiState())
    val uiState: StateFlow<WeightGoalUiState> = _uiState

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    startingWeightKg = profile.startingWeightKg ?: "",
                    goalWeightKg = profile.goalWeightKg ?: ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun updateStartingWeightKg(value: String) { _uiState.value = _uiState.value.copy(startingWeightKg = value) }
    fun updateGoalWeightKg(value: String) { _uiState.value = _uiState.value.copy(goalWeightKg = value) }

    fun save() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, saveError = null, saveSuccess = false)

        viewModelScope.launch {
            try {
                ApiClient.service.updateProfile(
                    UserProfileUpdateRequest(
                        startingWeightKg = state.startingWeightKg.toDoubleOrNull(),
                        goalWeightKg = state.goalWeightKg.toDoubleOrNull()
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = e.message ?: "Failed to save"
                )
            }
        }
    }
}