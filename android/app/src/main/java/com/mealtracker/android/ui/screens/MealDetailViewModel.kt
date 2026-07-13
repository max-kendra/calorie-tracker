package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Log
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
    val error: String? = null
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
}
