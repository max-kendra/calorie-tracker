package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
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

/**
 * Backs the calendar date-picker dialog -- `mealTypesLoggedByDay` is how
 * many DISTINCT meal types (of the 4: breakfast/lunch/dinner/snack) got
 * at least one log on that day, 0-4. This is deliberately NOT a raw log
 * count (someone could log 5 separate snacks and 0 of anything else --
 * that's still "1" for coloring purposes, matching the intent of
 * "how much of the day did you track", not "how many items did you eat").
 */
data class CalendarMonthState(
    val yearMonth: YearMonth,
    val isLoading: Boolean = true,
    val mealTypesLoggedByDay: Map<LocalDate, Int> = emptyMap()
)

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

    private val _calendarState = MutableStateFlow<CalendarMonthState?>(null)
    val calendarState: StateFlow<CalendarMonthState?> = _calendarState

    init {
        loadJournal(LocalDate.now())
    }

    /** Loads (or reloads) the tracked-day coloring for one month at a
     * time -- called when the calendar dialog opens and again whenever
     * the user navigates to a different month within it. */
    fun loadCalendarMonth(yearMonth: YearMonth) {
        _calendarState.value = CalendarMonthState(yearMonth, isLoading = true)
        viewModelScope.launch {
            try {
                val start = yearMonth.atDay(1)
                val end = yearMonth.atEndOfMonth()
                val logs = ApiClient.service.getLogsInRange(
                    start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
                val counts = logs
                    .groupBy { LocalDate.parse(it.date) }
                    .mapValues { (_, dayLogs) -> dayLogs.map { it.mealType }.distinct().size }
                _calendarState.value = CalendarMonthState(yearMonth, isLoading = false, mealTypesLoggedByDay = counts)
            } catch (e: Exception) {
                // Calendar coloring is a nice-to-have, not core
                // functionality -- on failure just show an empty (all
                // gray) month rather than blocking the date picker
                // itself from working.
                _calendarState.value = CalendarMonthState(yearMonth, isLoading = false)
            }
        }
    }

    fun clearCalendarState() {
        _calendarState.value = null
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