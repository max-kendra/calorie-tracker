package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.MealGoalSplitRequest
import com.mealtracker.android.network.models.MealGoalSplitsUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import kotlin.math.roundToInt

// Fixed order/display names - matches the Journal screen and the design
// doc's meal card order.
private val MEAL_TYPES = listOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks"
)

/**
 * Percentages are whole Ints, adjusted in single-percentage-point steps
 * (see the Slider's `steps` config in MealCalorieGoalScreen) - same
 * reasoning as MacronutrientsUiState: storing the value itself as an Int
 * means there's no hidden fractional drift between what's displayed and
 * what's actually sent to the backend, which requires an exact 100% sum.
 */
data class MealSplitRow(
    val mealType: String,
    val displayName: String,
    val percent: Int
) {
    fun kcal(kcalTarget: Int): Int = (kcalTarget * (percent / 100.0)).roundToInt()
}

data class MealCalorieGoalUiState(
    val isLoading: Boolean = true,
    // Null goalId means no active goal exists yet - there's nothing to
    // attach meal splits to, so the screen shows a "set up your calorie
    // goal first" message instead of sliders.
    val goalId: Int? = null,
    val kcalTarget: Int = 2000,
    val rows: List<MealSplitRow> = MEAL_TYPES.map { (type, name) -> MealSplitRow(type, name, 0) },
    val savedRows: List<MealSplitRow> = rows,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    val loadError: String? = null
) {
    val totalPct: Int get() = rows.sumOf { it.percent }
    val isValidTotal: Boolean get() = totalPct == 100
    val hasUnsavedChanges: Boolean get() = rows != savedRows
}

class MealCalorieGoalViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MealCalorieGoalUiState())
    val uiState: StateFlow<MealCalorieGoalUiState> = _uiState

    init {
        loadGoal()
    }

    private fun loadGoal() {
        viewModelScope.launch {
            try {
                val goal = ApiClient.service.getActiveGoal()
                val kcalTarget = goal.kcalTarget.toDoubleOrNull()?.roundToInt() ?: 2000
                val rows = MEAL_TYPES.map { (type, name) ->
                    val pct = goal.mealSplits
                        .find { it.mealType == type }
                        ?.pctOfKcal
                        ?.toDoubleOrNull()
                        ?.roundToInt() ?: 0
                    MealSplitRow(type, name, pct)
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    goalId = goal.id,
                    kcalTarget = kcalTarget,
                    rows = rows,
                    savedRows = rows
                )
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    // No goal yet - the screen will prompt the user to
                    // set one up first (see MealCalorieGoalScreen).
                    _uiState.value = _uiState.value.copy(isLoading = false, goalId = null)
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

    fun updatePercent(mealType: String, value: Int) {
        val state = _uiState.value
        val updatedRows = state.rows.map {
            if (it.mealType == mealType) it.copy(percent = value) else it
        }
        _uiState.value = state.copy(rows = updatedRows)
    }

    /** Reverts all editable rows back to the last-loaded/last-saved snapshot. */
    fun reset() {
        val state = _uiState.value
        _uiState.value = state.copy(
            rows = state.savedRows,
            saveError = null,
            saveSuccess = false
        )
    }

    fun save() {
        val state = _uiState.value
        val goalId = state.goalId ?: return
        if (!state.isValidTotal) return // UI should already prevent calling this

        _uiState.value = state.copy(isSaving = true, saveError = null)

        viewModelScope.launch {
            try {
                ApiClient.service.updateMealSplits(
                    goalId,
                    MealGoalSplitsUpdateRequest(
                        splits = state.rows.map {
                            MealGoalSplitRequest(it.mealType, it.percent.toDouble())
                        }
                    )
                )
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true,
                    savedRows = state.rows
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
