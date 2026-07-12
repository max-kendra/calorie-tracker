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
 * for that meal_type, how many kcal that adds up to (summed from the
 * FROZEN kcal_logged values, not live-recomputed -- see design doc on
 * why logs snapshot at write time), and the goal for that meal (derived
 * from the active Goal's meal_splits, already computed server-side).
 */
data class MealBucket(
    val mealType: String,
    val displayName: String,
    val logs: List<Log>,
    val eatenKcal: Int,
    val goalKcal: Int
)

sealed class JournalUiState {
    object Loading : JournalUiState()
    data class Success(val date: LocalDate, val buckets: List<MealBucket>) : JournalUiState()
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
                    val eatenKcal = mealLogs.sumOf { it.kcalLogged }
                    val goalKcal = goal.mealSplits
                        .find { it.mealType == mealType }
                        ?.computedTotals
                        ?.kcal ?: 0

                    MealBucket(
                        mealType = mealType,
                        displayName = displayName,
                        logs = mealLogs,
                        eatenKcal = eatenKcal,
                        goalKcal = goalKcal
                    )
                }

                _uiState.value = JournalUiState.Success(date, buckets)
            } catch (e: Exception) {
                // Common causes: no active goal set yet on the backend
                // (GET /goals/active returns 404 if none exists), or the
                // usual connectivity issues from the Home screen check.
                _uiState.value = JournalUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
