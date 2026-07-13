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

/**
 * One meal's worth of data for the Journal screen -- the logged items
 * for that meal_type, and both kcal AND per-macro eaten/goal figures
 * (goal figures come from the active Goal's meal_splits[].computed_totals,
 * which the backend already derives as overall-goal-macros x this
 * meal's percentage -- see design doc).
 */
data class MealBucket(
    val mealType: String,
    val displayName: String,
    val logs: List<Log>,
    val eatenKcal: Int,
    val goalKcal: Int,
    val eatenFat: Int,
    val goalFat: Int,
    val eatenProtein: Int,
    val goalProtein: Int,
    val eatenCarbs: Int,
    val goalCarbs: Int,
    val eatenFiber: Int,
    val goalFiber: Int
)

/** Whole-day totals -- sum of all meals' eaten values, compared against
 * the active Goal's own top-level targets (not derived from meal_splits,
 * since those are just a slice of the same overall targets). */
data class DailyTotals(
    val eatenKcal: Int,
    val goalKcal: Int,
    val eatenFat: Int,
    val goalFat: Int,
    val eatenProtein: Int,
    val goalProtein: Int,
    val eatenCarbs: Int,
    val goalCarbs: Int,
    val eatenFiber: Int,
    val goalFiber: Int
)

sealed class JournalUiState {
    object Loading : JournalUiState()
    data class Success(
        val date: LocalDate,
        val dailyTotals: DailyTotals,
        val buckets: List<MealBucket>
    ) : JournalUiState()
    data class Error(val message: String) : JournalUiState()
}

// Fixed order/display names -- matches the design doc's meal card order.
private val MEAL_TYPES = listOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks"
)

class JournalViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<JournalUiState>(JournalUiState.Loading)
    val uiState: StateFlow<JournalUiState> = _uiState

    init {
        loadJournal(LocalDate.now())
    }

    fun loadJournal(date: LocalDate) {
        _uiState.value = JournalUiState.Loading
        viewModelScope.launch {
            try {
                val dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val logs = ApiClient.service.getLogs(dateString)
                val goal = ApiClient.service.getActiveGoal()

                val logsByMealType = logs.groupBy { it.mealType }

                val buckets = MEAL_TYPES.map { (mealType, displayName) ->
                    val mealLogs = logsByMealType[mealType] ?: emptyList()
                    val split = goal.mealSplits.find { it.mealType == mealType }
                    val goalTotals = split?.computedTotals

                    MealBucket(
                        mealType = mealType,
                        displayName = displayName,
                        logs = mealLogs,
                        eatenKcal = mealLogs.sumOf { it.kcalLogged },
                        goalKcal = goalTotals?.kcal ?: 0,
                        eatenFat = mealLogs.sumOf { it.fatGLogged },
                        goalFat = goalTotals?.fatG ?: 0,
                        eatenProtein = mealLogs.sumOf { it.proteinGLogged },
                        goalProtein = goalTotals?.proteinG ?: 0,
                        eatenCarbs = mealLogs.sumOf { it.carbsGLogged },
                        goalCarbs = goalTotals?.carbsG ?: 0,
                        eatenFiber = mealLogs.sumOf { it.fiberGLogged },
                        goalFiber = goalTotals?.fiberG ?: 0
                    )
                }

                val dailyTotals = DailyTotals(
                    eatenKcal = logs.sumOf { it.kcalLogged },
                    goalKcal = goal.kcalTarget.toDoubleOrNull()?.toInt() ?: 0,
                    eatenFat = logs.sumOf { it.fatGLogged },
                    goalFat = goal.fatGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    eatenProtein = logs.sumOf { it.proteinGLogged },
                    goalProtein = goal.proteinGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    eatenCarbs = logs.sumOf { it.carbsGLogged },
                    goalCarbs = goal.carbsGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    eatenFiber = logs.sumOf { it.fiberGLogged },
                    goalFiber = goal.fiberGTarget.toDoubleOrNull()?.toInt() ?: 0
                )

                _uiState.value = JournalUiState.Success(date, dailyTotals, buckets)
            } catch (e: Exception) {
                // Common causes: no active goal set yet on the backend
                // (GET /goals/active returns 404 if none exists), or the
                // usual connectivity issues from the Home screen check.
                _uiState.value = JournalUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
