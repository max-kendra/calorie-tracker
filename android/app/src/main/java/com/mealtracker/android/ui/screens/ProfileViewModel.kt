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

// Standard nutrition-labeling kcal-per-gram conversion factors -- same
// constants used in MacronutrientsViewModel, needed here to preserve a
// goal's macro RATIO when its kcal_target changes (see saveAsGoal()).
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_FIBER = 2.0

// Default macro split used only when creating a BRAND NEW goal (no
// existing ratio to preserve) -- matches MacronutrientsViewModel's defaults.
private const val DEFAULT_FAT_PCT = 25.0
private const val DEFAULT_PROTEIN_PCT = 25.0
private const val DEFAULT_CARBS_PCT = 47.0
private const val DEFAULT_FIBER_PCT = 3.0
// Matches the backend's rounding granularity for recommended_kcal, so
// stepping stays consistent with what the calculation itself produces.
private const val KCAL_STEP = 25

data class ProfileUiState(
    val isLoading: Boolean = true,
    val name: String = "",
    val heightCm: String = "",
    val age: String = "",
    val weightKg: String = "",
    val primaryHormone: String? = null, // "testosterone" | "estrogen" | "other" | null
    val activityLevel: String? = null, // "sedentary" | "light" | "moderate" | "active" | "very_active"
    val goalType: String? = null, // "lose" | "maintain" | "gain"
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    val loadError: String? = null,
    // Whether an active Goal already exists on the backend -- determines
    // whether saveAsGoal() creates or updates, and whether the
    // Macronutrients/Meal Calorie Goal screens are unlocked yet.
    val existingGoalId: Int? = null,
    val isCalculating: Boolean = false,
    val calcResult: KcalGoalCalculationResult? = null,
    val calcError: String? = null,
    // User-adjustable, defaults to calcResult.recommendedKcal once a
    // calculation succeeds. Deliberately NOT hard-constrained to
    // [kcal_low, kcal_high] -- the suggested range is shown as reference
    // text, but the final decision belongs to the user, same as any
    // commercial tracker lets you set a goal below what it recommends.
    // A visible warning (not a block) appears if this drops below 1500.
    val editableKcalTarget: Int = 0,
    val isSavingGoal: Boolean = false,
    val goalSaveError: String? = null,
    val goalSaveSuccess: Boolean = false
) {
    /**
     * Required fields for the kcal-goal calculation to work (matches the
     * backend's own required-field check in calculate-kcal-goal).
     * primary_hormone is deliberately NOT required -- the backend falls
     * back to an averaged constant if it's unset (see design doc).
     */
    val isProfileComplete: Boolean
        get() = heightCm.toDoubleOrNull() != null &&
            age.toIntOrNull() != null &&
            weightKg.toDoubleOrNull() != null &&
            activityLevel != null &&
            goalType != null

    /**
     * Gates access to the Macronutrients / Meal Calorie Goal screens --
     * per design: profile must be filled in AND an active goal (with a
     * real kcal_target) must exist before those screens make sense.
     */
    val isUnlocked: Boolean get() = isProfileComplete && existingGoalId != null

    /** Warning (not a block) shown when the user has adjusted the target
     * below the general safety floor -- see design doc discussion: this
     * is a soft warning since the final call belongs to the user, not
     * something the app should hard-prevent. */
    val belowSafetyFloor: Boolean get() = calcResult != null && editableKcalTarget < 1500
}

class ProfileViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()

                // Separately check whether an active goal exists --
                // 404 just means "none yet," not an error.
                val goalId = try {
                    ApiClient.service.getActiveGoal().id
                } catch (e: HttpException) {
                    if (e.code() == 404) null else throw e
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    name = profile.name ?: "",
                    heightCm = profile.heightCm ?: "",
                    age = profile.age?.toString() ?: "",
                    weightKg = profile.weightKg ?: "",
                    primaryHormone = profile.primaryHormone,
                    activityLevel = profile.activityLevel,
                    goalType = profile.goalType,
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

    fun updateName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun updateHeightCm(value: String) {
        _uiState.value = _uiState.value.copy(heightCm = value)
    }

    fun updateAge(value: String) {
        _uiState.value = _uiState.value.copy(age = value)
    }

    fun updateWeightKg(value: String) {
        _uiState.value = _uiState.value.copy(weightKg = value)
    }

    fun updatePrimaryHormone(value: String) {
        _uiState.value = _uiState.value.copy(primaryHormone = value)
    }

    fun updateActivityLevel(value: String) {
        _uiState.value = _uiState.value.copy(activityLevel = value)
    }

    fun updateGoalType(value: String) {
        _uiState.value = _uiState.value.copy(goalType = value)
    }

    fun saveProfile() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, saveError = null)

        viewModelScope.launch {
            try {
                ApiClient.service.updateProfile(
                    UserProfileUpdateRequest(
                        name = state.name.ifBlank { null },
                        heightCm = state.heightCm.toDoubleOrNull(),
                        age = state.age.toIntOrNull(),
                        weightKg = state.weightKg.toDoubleOrNull(),
                        primaryHormone = state.primaryHormone,
                        activityLevel = state.activityLevel,
                        goalType = state.goalType
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

    fun calculate() {
        val state = _uiState.value
        if (!state.isProfileComplete) return

        _uiState.value = state.copy(isCalculating = true, calcError = null, calcResult = null)

        viewModelScope.launch {
            try {
                val result = ApiClient.service.calculateKcalGoal()
                _uiState.value = _uiState.value.copy(
                    isCalculating = false,
                    calcResult = result,
                    editableKcalTarget = result.recommendedKcal
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCalculating = false,
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
     * grams) is preserved -- e.g. if it was previously 30% protein, it
     * stays 30% protein at the new kcal total, rather than keeping the
     * old gram amounts (which would silently change the effective
     * percentage). If no goal exists yet, sensible defaults are used.
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
