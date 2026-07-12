package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.GoalCreateRequest
import com.mealtracker.android.network.models.GoalUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Standard nutrition-labeling kcal-per-gram conversion factors (matches
// the pie-chart math discussed in the design doc: protein/carbs = 4,
// fat = 9, fiber = 2).
private const val KCAL_PER_G_PROTEIN = 4.0
private const val KCAL_PER_G_CARBS = 4.0
private const val KCAL_PER_G_FAT = 9.0
private const val KCAL_PER_G_FIBER = 2.0

data class MacroRow(
    val percent: Double,
    val grams: Double,
    val kcal: Double
)

data class MacronutrientsUiState(
    val isLoading: Boolean = true,
    val existingGoalId: Int? = null,
    val kcalTarget: String = "2000",
    val fatPct: Double = 25.0,
    val proteinPct: Double = 25.0,
    val carbsPct: Double = 47.0,
    val fiberPct: Double = 3.0,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    val loadError: String? = null
) {
    val totalPct: Double get() = fatPct + proteinPct + carbsPct + fiberPct
    // Small tolerance for floating point/slider rounding -- matches the
    // "must sum to exactly 100%" hard gate from the design doc, without
    // being overly strict about floating-point noise.
    val isValidTotal: Boolean get() = kotlin.math.abs(totalPct - 100.0) < 0.5

    private val kcalTargetValue: Double get() = kcalTarget.toDoubleOrNull() ?: 0.0

    val fat: MacroRow get() = macroRow(fatPct, KCAL_PER_G_FAT)
    val protein: MacroRow get() = macroRow(proteinPct, KCAL_PER_G_PROTEIN)
    val carbs: MacroRow get() = macroRow(carbsPct, KCAL_PER_G_CARBS)
    val fiber: MacroRow get() = macroRow(fiberPct, KCAL_PER_G_FIBER)

    private fun macroRow(pct: Double, kcalPerGram: Double): MacroRow {
        val kcal = kcalTargetValue * (pct / 100.0)
        val grams = kcal / kcalPerGram
        return MacroRow(percent = pct, grams = grams, kcal = kcal)
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
                val kcalTarget = goal.kcalTarget.toDoubleOrNull() ?: 2000.0

                // Back-calculate percentages from the stored gram targets,
                // so editing an existing goal starts from its real values
                // rather than resetting to defaults.
                fun pctOf(grams: String, kcalPerGram: Double): Double {
                    val g = grams.toDoubleOrNull() ?: 0.0
                    return if (kcalTarget > 0) (g * kcalPerGram / kcalTarget) * 100.0 else 0.0
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    existingGoalId = goal.id,
                    kcalTarget = goal.kcalTarget,
                    fatPct = pctOf(goal.fatGTarget, KCAL_PER_G_FAT),
                    proteinPct = pctOf(goal.proteinGTarget, KCAL_PER_G_PROTEIN),
                    carbsPct = pctOf(goal.carbsGTarget, KCAL_PER_G_CARBS),
                    fiberPct = pctOf(goal.fiberGTarget, KCAL_PER_G_FIBER)
                )
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    // No active goal yet -- perfectly normal for a fresh
                    // setup, just use the defaults already in the state.
                    _uiState.value = _uiState.value.copy(isLoading = false)
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

    fun updateKcalTarget(value: String) {
        _uiState.value = _uiState.value.copy(kcalTarget = value)
    }

    fun updateFatPct(value: Double) {
        _uiState.value = _uiState.value.copy(fatPct = value)
    }

    fun updateProteinPct(value: Double) {
        _uiState.value = _uiState.value.copy(proteinPct = value)
    }

    fun updateCarbsPct(value: Double) {
        _uiState.value = _uiState.value.copy(carbsPct = value)
    }

    fun updateFiberPct(value: Double) {
        _uiState.value = _uiState.value.copy(fiberPct = value)
    }

    fun save() {
        val state = _uiState.value
        if (!state.isValidTotal) return // UI should already prevent calling this

        _uiState.value = state.copy(isSaving = true, saveError = null)

        viewModelScope.launch {
            try {
                val existingId = state.existingGoalId
                if (existingId != null) {
                    ApiClient.service.updateGoal(
                        existingId,
                        GoalUpdateRequest(
                            kcalTarget = state.kcalTarget.toDoubleOrNull(),
                            proteinGTarget = state.protein.grams,
                            carbsGTarget = state.carbs.grams,
                            fatGTarget = state.fat.grams,
                            fiberGTarget = state.fiber.grams
                        )
                    )
                } else {
                    ApiClient.service.createGoal(
                        GoalCreateRequest(
                            startDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                            kcalTarget = state.kcalTarget.toDoubleOrNull() ?: 2000.0,
                            proteinGTarget = state.protein.grams,
                            carbsGTarget = state.carbs.grams,
                            fatGTarget = state.fat.grams,
                            fiberGTarget = state.fiber.grams
                        )
                    )
                }
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
