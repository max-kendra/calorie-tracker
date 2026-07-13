package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.RecipeCreateRequest
import com.mealtracker.android.network.models.RecipeIngredientCreateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MEAL_DISPLAY_NAMES = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks"
)

data class MealDetailUiState(
    val isLoading: Boolean = true,
    val displayName: String = "",
    val logs: List<Log> = emptyList(),
    val eatenKcal: Int = 0,
    val goalKcal: Int = 0,
    val eatenFat: Int = 0,
    val goalFat: Int = 0,
    val eatenProtein: Int = 0,
    val goalProtein: Int = 0,
    val eatenCarbs: Int = 0,
    val goalCarbs: Int = 0,
    val eatenFiber: Int = 0,
    val goalFiber: Int = 0,
    val error: String? = null,
    // "Save this meal" (star icon) state
    val showSaveMealDialog: Boolean = false,
    val mealNameInput: String = "",
    val isSavingMeal: Boolean = false,
    val saveMealError: String? = null,
    val saveMealSuccess: Boolean = false
)

/**
 * Fetches independently from JournalViewModel (its own /logs +
 * /goals/active calls, scoped to just this one meal/date) rather than
 * receiving data passed through navigation -- simpler than threading
 * complex state through nav arguments, and keeps this screen's data
 * fresh if the user navigates back and forth.
 */
class MealDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MealDetailUiState())
    val uiState: StateFlow<MealDetailUiState> = _uiState

    fun load(date: LocalDate, mealType: String) {
        _uiState.value = MealDetailUiState(isLoading = true, displayName = MEAL_DISPLAY_NAMES[mealType] ?: mealType)

        viewModelScope.launch {
            try {
                val dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val logs = ApiClient.service.getLogs(dateString, mealType)
                val goal = ApiClient.service.getActiveGoal()
                val goalTotals = goal.mealSplits.find { it.mealType == mealType }?.computedTotals

                _uiState.value = MealDetailUiState(
                    isLoading = false,
                    displayName = MEAL_DISPLAY_NAMES[mealType] ?: mealType,
                    logs = logs,
                    eatenKcal = logs.sumOf { it.kcalLogged },
                    goalKcal = goalTotals?.kcal ?: 0,
                    eatenFat = logs.sumOf { it.fatGLogged },
                    goalFat = goalTotals?.fatG ?: 0,
                    eatenProtein = logs.sumOf { it.proteinGLogged },
                    goalProtein = goalTotals?.proteinG ?: 0,
                    eatenCarbs = logs.sumOf { it.carbsGLogged },
                    goalCarbs = goalTotals?.carbsG ?: 0,
                    eatenFiber = logs.sumOf { it.fiberGLogged },
                    goalFiber = goalTotals?.fiberG ?: 0
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    fun openSaveMealDialog() {
        _uiState.value = _uiState.value.copy(showSaveMealDialog = true, mealNameInput = "")
    }

    fun dismissSaveMealDialog() {
        _uiState.value = _uiState.value.copy(showSaveMealDialog = false)
    }

    fun updateMealNameInput(value: String) {
        _uiState.value = _uiState.value.copy(mealNameInput = value)
    }

    /**
     * Snapshots the currently-logged items into a new Recipe with
     * recipe_type="meal" (see backend design doc: a saved Meal is just a
     * recipe with servings=1, editable afterward like any recipe).
     *
     * LIMITATION: only logs that reference a real item AND were logged
     * directly in grams (no serving_size_id) are included -- converting
     * a serving-size-based quantity to grams needs an extra lookup
     * (the ServingSize's weight_g) that isn't fetched here. Recipe-based
     * logs are also skipped, since recipe_ingredients can only reference
     * items, not other recipes. Both are real gaps, not silent bugs --
     * worth fixing if this turns out to matter in practice once logging
     * itself is built (this screen currently has no way to create logs
     * yet, so `logs` will typically be empty regardless).
     */
    fun saveAsMeal() {
        val state = _uiState.value
        val name = state.mealNameInput.trim()
        if (name.isEmpty()) return

        val ingredients = state.logs
            .filter { it.itemId != null && it.servingSizeId == null }
            .mapNotNull { log ->
                val itemId = log.itemId ?: return@mapNotNull null
                val grams = log.quantity.toDoubleOrNull() ?: return@mapNotNull null
                RecipeIngredientCreateRequest(itemId = itemId, quantityG = grams)
            }

        _uiState.value = state.copy(isSavingMeal = true, saveMealError = null)

        viewModelScope.launch {
            try {
                ApiClient.service.createRecipe(
                    RecipeCreateRequest(name = name, recipeType = "meal", servings = 1.0, ingredients = ingredients)
                )
                _uiState.value = _uiState.value.copy(
                    isSavingMeal = false,
                    saveMealSuccess = true,
                    showSaveMealDialog = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingMeal = false,
                    saveMealError = e.message ?: "Failed to save meal"
                )
            }
        }
    }
}
