package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class OnboardingGateUiState(
    val isChecking: Boolean = true,
    // Null while isChecking is true; the destination is only decided
    // once this resolves. Defaults to true (rather than false) if the
    // check itself fails for some reason (e.g. transient network error)
    // -- see checkSetupComplete()'s catch block for why.
    val needsOnboarding: Boolean? = null
)

/**
 * Runs once at app launch (see the "gate" route in AppNavHost) to decide
 * whether to send the user into the required OnboardingScreen flow or
 * straight to the normal Home/Journal tabs. "Complete" means: profile
 * has name/height/age, a weight goal is set, AND an active calorie goal
 * exists (macro grams always exist once a goal does, since
 * CalorieGoalViewModel.saveAsGoal() always sets them -- see that file --
 * so there's no separate "macros missing" case to check for here).
 */
class OnboardingGateViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingGateUiState())
    val uiState: StateFlow<OnboardingGateUiState> = _uiState

    fun checkSetupComplete() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()
                val profileComplete = profile.name?.isNotBlank() == true &&
                    profile.heightCm != null &&
                    profile.age != null &&
                    profile.startingWeightKg != null &&
                    profile.goalWeightKg != null

                val hasActiveGoal = try {
                    ApiClient.service.getActiveGoal()
                    true
                } catch (e: HttpException) {
                    if (e.code() == 404) false else throw e
                }

                _uiState.value = OnboardingGateUiState(
                    isChecking = false,
                    needsOnboarding = !(profileComplete && hasActiveGoal)
                )
            } catch (e: Exception) {
                // Network/server error during the check itself (not "no
                // goal exists yet", which is handled above) -- defaults
                // to true rather than silently letting the user into a
                // Home screen that will itself immediately break on
                // missing goal data (the exact 404-on-meal-split bug
                // this whole gate exists to prevent). Worse to
                // occasionally re-show onboarding unnecessarily on a
                // flaky connection than to let this check's own failure
                // recreate the original bug.
                _uiState.value = OnboardingGateUiState(isChecking = false, needsOnboarding = true)
            }
        }
    }
}