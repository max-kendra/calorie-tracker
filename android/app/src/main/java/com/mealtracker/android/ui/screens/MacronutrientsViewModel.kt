package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.GoalUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import kotlin.math.roundToInt

// Standard nutrition-labeling kcal-per-gram conversion factors (matches
// the pie-chart math discussed in the design doc: protein/carbs = 4,
// fat = 9, fiber = 2).
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_FIBER = 2.0

data class MacroRow(
    val percent: Int,
    val grams: Int,
    val kcal: Int
)

/**
 * Percentages are whole Ints, adjusted in single-percentage-point steps
 * (see the Slider's `steps` config in MacronutrientsScreen) - storing
 * the value itself as a whole Int means there's no hidden fractional
 * drift between what's displayed and what's actually sent to the
 * backend, which requires an exact 100% sum.
 *
 * kcalTarget is READ-ONLY here - it's set exclusively via the Profile
 * screen's calorie-goal calculator now, not editable on this screen.
 * This screen requires an active Goal to already exist (Profile gates
 * navigation here until one does, but we still handle the missing-goal
 * case defensively in case of direct navigation).
 */
data class MacronutrientsUiState(
    val isLoading: Boolean = true,
    val goalMissing: Boolean = false,
    val existingGoalId: Int? = null,
    val kcalTarget: Double = 0.0,
    val fatPct: Int = 25,
    val proteinPct: Int = 25,
    val carbsPct: Int = 47,
    val fiberPct: Int = 3,
    val savedFatPct: Int = 25,
    val savedProteinPct: Int = 25,
    val savedCarbsPct: Int = 47,
    val savedFiberPct: Int = 3,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    val loadError: String? = null
) {
    val totalPct: Int get() = fatPct + proteinPct + carbsPct + fiberPct
    val isValidTotal: Boolean get() = totalPct == 100

    val hasUnsavedChanges: Boolean
        get() = fatPct != savedFatPct ||
            proteinPct != savedProteinPct ||
            carbsPct != savedCarbsPct ||
            fiberPct != savedFiberPct

    val fat: MacroRow get() = macroRow(fatPct, KCAL_PER_G_FAT)
    val protein: MacroRow get() = macroRow(proteinPct, KCAL_PER_G_PROTEIN)
    val carbs: MacroRow get() = macroRow(carbsPct, KCAL_PER_G_CARBS)
    val fiber: MacroRow get() = macroRow(fiberPct, KCAL_PER_G_FIBER)

    private fun macroRow(pct: Int, kcalPerGram: Double): MacroRow {
        val kcal = kcalTarget * (pct / 100.0)
        val grams = kcal / kcalPerGram
        return MacroRow(percent = pct, grams = grams.roundToInt(), kcal = kcal.roundToInt())
    }
}

class MacronutrientsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MacronutrientsUiState())
    val uiState: StateFlow<MacronutrientsUiState> = _uiState

    init {
        loadExistingGoal()
    }

    private fun loadExistingGoal() {
        viewModelScope.launch {
            try {
                val goal = ApiClient.service.getActiveGoal()
                val kcalTarget = goal.kcalTarget.toDoubleOrNull() ?: 0.0

                fun pctOf(grams: String, kcalPerGram: Double): Int {
                    val g = grams.toDoubleOrNull() ?: 0.0
                    return if (kcalTarget > 0) ((g * kcalPerGram / kcalTarget) * 100.0).roundToInt() else 0
                }

                val fatPct = pctOf(goal.fatGTarget, KCAL_PER_G_FAT)
                val proteinPct = pctOf(goal.proteinGTarget, KCAL_PER_G_PROTEIN)
                val carbsPct = pctOf(goal.carbsGTarget, KCAL_PER_G_CARBS)
                val fiberPct = pctOf(goal.fiberGTarget, KCAL_PER_G_FIBER)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    goalMissing = false,
                    existingGoalId = goal.id,
                    kcalTarget = kcalTarget,
                    fatPct = fatPct,
                    proteinPct = proteinPct,
                    carbsPct = carbsPct,
                    fiberPct = fiberPct,
                    savedFatPct = fatPct,
                    savedProteinPct = proteinPct,
                    savedCarbsPct = carbsPct,
                    savedFiberPct = fiberPct
                )
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    _uiState.value = _uiState.value.copy(isLoading = false, goalMissing = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadError = "Server error (${e.code()})"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun updateFatPct(value: Int) {
        _uiState.value = _uiState.value.copy(fatPct = value)
    }

    fun updateProteinPct(value: Int) {
        _uiState.value = _uiState.value.copy(proteinPct = value)
    }

    fun updateCarbsPct(value: Int) {
        _uiState.value = _uiState.value.copy(carbsPct = value)
    }

    fun updateFiberPct(value: Int) {
        _uiState.value = _uiState.value.copy(fiberPct = value)
    }

    /** Reverts all editable fields back to the last-loaded/last-saved snapshot. */
    fun reset() {
        val state = _uiState.value
        _uiState.value = state.copy(
            fatPct = state.savedFatPct,
            proteinPct = state.savedProteinPct,
            carbsPct = state.savedCarbsPct,
            fiberPct = state.savedFiberPct,
            saveError = null,
            saveSuccess = false
        )
    }

    fun save() {
        val state = _uiState.value
        val goalId = state.existingGoalId ?: return
        if (!state.isValidTotal) return // UI should already prevent calling this

        _uiState.value = state.copy(isSaving = true, saveError = null)

        viewModelScope.launch {
            try {
                ApiClient.service.updateGoal(
                    goalId,
                    GoalUpdateRequest(
                        proteinGTarget = state.protein.grams.toDouble(),
                        carbsGTarget = state.carbs.grams.toDouble(),
                        fatGTarget = state.fat.grams.toDouble(),
                        fiberGTarget = state.fiber.grams.toDouble()
                    )
                )
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    savedFatPct = state.fatPct,
                    savedProteinPct = state.proteinPct,
                    savedCarbsPct = state.carbsPct,
                    savedFiberPct = state.fiberPct
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = e.message ?: "Failed to save"
                )
            }
        }
    }
}
