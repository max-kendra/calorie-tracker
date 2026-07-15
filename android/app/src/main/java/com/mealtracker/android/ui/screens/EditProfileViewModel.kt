package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.UserProfileUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val name: String = "",
    val heightCm: String = "",
    val age: String = "",
    val primaryHormone: String? = null, // "testosterone" | "estrogen" | "other" | null
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false
)

/**
 * Just the identity/body-basics fields (name, height, age, dominant
 * hormone) -- weight, activity level, and goal type live on
 * CalorieGoalViewModel instead, since they're specifically TDEE-
 * calculation inputs rather than general profile info (see design
 * discussion: "calorie goal, that's where the TDEE calculation should
 * be").
 */
class EditProfileViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    name = profile.name ?: "",
                    heightCm = profile.heightCm?.toString() ?: "",
                    age = profile.age?.toString() ?: "",
                    primaryHormone = profile.primaryHormone
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun updateName(value: String) { _uiState.value = _uiState.value.copy(name = value) }
    fun updateHeightCm(value: String) { _uiState.value = _uiState.value.copy(heightCm = value) }
    fun updateAge(value: String) { _uiState.value = _uiState.value.copy(age = value) }
    fun updatePrimaryHormone(value: String) { _uiState.value = _uiState.value.copy(primaryHormone = value) }

    fun saveProfile() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, saveError = null, saveSuccess = false)

        viewModelScope.launch {
            try {
                ApiClient.service.updateProfile(
                    UserProfileUpdateRequest(
                        name = state.name.ifBlank { null },
                        heightCm = state.heightCm.toIntOrNull(),
                        age = state.age.toIntOrNull(),
                        primaryHormone = state.primaryHormone
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