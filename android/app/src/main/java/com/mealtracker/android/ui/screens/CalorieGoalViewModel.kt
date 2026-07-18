package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.GoalCreateRequest
import com.mealtracker.android.network.models.GoalUpdateRequest
import com.mealtracker.android.network.models.KcalGoalCalculationResult
import com.mealtracker.android.network.models.UserProfileUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Standard nutrition-labeling kcal-per-gram conversion factors - needed
// to preserve a goal's macro RATIO when its kcal_target changes (see
// saveAsGoal()).
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_FIBER = 2.0

// Default macro split used only when creating a BRAND NEW goal (no
// existing ratio to preserve) - matches MacronutrientsViewModel's defaults.
private const val DEFAULT_FAT_PCT = 25.0
private const val DEFAULT_PROTEIN_PCT = 25.0
private const val DEFAULT_CARBS_PCT = 47.0
private const val DEFAULT_FIBER_PCT = 3.0
// Matches the backend's rounding granularity for recommended_kcal, so
// stepping stays consistent with what the calculation itself produces.
private const val KCAL_STEP = 25

data class CalorieGoalUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,

    // TDEE-calculation inputs, stored on the profile (weight_kg,
    // activity_level, goal_type) - height/age come from the profile
    // too but are edited on the separate Edit Profile screen, not here.
    val weightKg: String = "",
    val activityLevel: String? = null,
    val goalType: String? = null,
    val heightCm: Int? = null,
    val age: Int? = null,

    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,

    val existingGoalId: Int? = null,
    val isCalculating: Boolean = false,
    val calcResult: KcalGoalCalculationResult? = null,
    val calcError: String? = null,
    val editableKcalTarget: Int = 0,
    val isSavingGoal: Boolean = false,
    val goalSaveError: String? = null,
    val goalSaveSuccess: Boolean = false
) {
    /** Required fields for the kcal-goal calculation to work (matches
     * the backend's own required-field check). */
    val isProfileComplete: Boolean
        get() = heightCm != null &&
            age != null &&
            weightKg.toDoubleOrNull() != null &&
            activityLevel != null &&
            goalType != null

    val belowSafetyFloor: Boolean get() = calcResult != null && editableKcalTarget < 1500
}

class CalorieGoalViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CalorieGoalUiState())
    val uiState: StateFlow<CalorieGoalUiState> = _uiState

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()
                val goalId = try {
                    ApiClient.service.getActiveGoal().id
                } catch (e: HttpException) {
                    if (e.code() == 404) null else throw e
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    weightKg = profile.weightKg ?: "",
                    activityLevel = profile.activityLevel,
                    goalType = profile.goalType,
                    heightCm = profile.heightCm,
                    age = profile.age,
                    existingGoalId = goalId
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun updateWeightKg(value: String) { _uiState.value = _uiState.value.copy(weightKg = value) }
    fun updateActivityLevel(value: String) { _uiState.value = _uiState.value.copy(activityLevel = value) }
    fun updateGoalType(value: String) { _uiState.value = _uiState.value.copy(goalType = value) }

    /** Saves weight/activity/goal_type to the profile - must happen
     * before calculate(), since the backend's calculate-kcal-goal
     * endpoint reads these from the STORED profile, not from a request
     * body (see backend docstring). */
    fun saveInputsThenCalculate() {
        val state = _uiState.value
        if (!state.isProfileComplete) return

        _uiState.value = state.copy(isSaving = true, saveError = null, isCalculating = true, calcError = null, calcResult = null)

        viewModelScope.launch {
            try {
                ApiClient.service.updateProfile(
                    UserProfileUpdateRequest(
                        weightKg = state.weightKg.toDoubleOrNull(),
                        activityLevel = state.activityLevel,
                        goalType = state.goalType
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = true, saveSuccess = true)

                val result = ApiClient.service.calculateKcalGoal()
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isCalculating = false,
                    calcResult = result,
                    editableKcalTarget = result.recommendedKcal
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isCalculating = false,
                    saveError = e.message ?: "Failed to save",
                    calcError = e.message ?: "Calculation failed"
                )
            }
        }
    }

    fun incrementKcalTarget() {
        val state = _uiState.value
        _uiState.value = state.copy(editableKcalTarget = state.editableKcalTarget + KCAL_STEP)
    }

    fun decrementKcalTarget() {
        val state = _uiState.value
        _uiState.value = state.copy(editableKcalTarget = state.editableKcalTarget - KCAL_STEP)
    }

    /**
     * Saves the calculated kcal target as the active Goal's kcal_target.
     * If a goal already exists, its current macro RATIO (not absolute
     * grams) is preserved. If no goal exists yet, sensible defaults are
     * used. See ProfileViewModel's (now removed) original version of
     * this function for the same logic - unchanged, just relocated.
     */
    fun saveAsGoal() {
        val state = _uiState.value
        if (state.editableKcalTarget <= 0) return
        val newKcal = state.editableKcalTarget.toDouble()

        _uiState.value = state.copy(isSavingGoal = true, goalSaveError = null)

        viewModelScope.launch {
            try {
                val existingId = state.existingGoalId
                val savedGoal = if (existingId != null) {
                    val existing = ApiClient.service.getActiveGoal()
                    val oldKcal = existing.kcalTarget.toDoubleOrNull() ?: newKcal

                    fun preserveRatio(gramsStr: String, kcalPerGram: Double): Double {
                        val grams = gramsStr.toDoubleOrNull() ?: 0.0
                        val pctOfOld = if (oldKcal > 0) (grams * kcalPerGram / oldKcal) else 0.0
                        return (pctOfOld * newKcal) / kcalPerGram
                    }

                    ApiClient.service.updateGoal(
                        existingId,
                        GoalUpdateRequest(
                            kcalTarget = newKcal,
                            proteinGTarget = preserveRatio(existing.proteinGTarget, KCAL_PER_G_PROTEIN),
                            carbsGTarget = preserveRatio(existing.carbsGTarget, KCAL_PER_G_CARBS),
                            fatGTarget = preserveRatio(existing.fatGTarget, KCAL_PER_G_FAT),
                            fiberGTarget = preserveRatio(existing.fiberGTarget, KCAL_PER_G_FIBER)
                        )
                    )
                } else {
                    fun gramsFor(pct: Double, kcalPerGram: Double) = (newKcal * (pct / 100.0)) / kcalPerGram

                    ApiClient.service.createGoal(
                        GoalCreateRequest(
                            startDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                            kcalTarget = newKcal,
                            proteinGTarget = gramsFor(DEFAULT_PROTEIN_PCT, KCAL_PER_G_PROTEIN),
                            carbsGTarget = gramsFor(DEFAULT_CARBS_PCT, KCAL_PER_G_CARBS),
                            fatGTarget = gramsFor(DEFAULT_FAT_PCT, KCAL_PER_G_FAT),
                            fiberGTarget = gramsFor(DEFAULT_FIBER_PCT, KCAL_PER_G_FIBER)
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isSavingGoal = false,
                    goalSaveSuccess = true,
                    existingGoalId = savedGoal.id
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingGoal = false,
                    goalSaveError = e.message ?: "Failed to save goal"
                )
            }
        }
    }
}