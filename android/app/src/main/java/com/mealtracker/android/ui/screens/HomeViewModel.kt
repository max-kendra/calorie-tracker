package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** kcal + all four tracked macros in one bundle -- reused for both a
 * single day's totals and the whole week's totals, so the same math
 * (sum logs, compare to a goal) works at either scope. */
data class MacroSet(
    val kcal: Int = 0,
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0,
    val fiber: Int = 0
) {
    companion object {
        fun fromLogs(logs: List<Log>) = MacroSet(
            kcal = logs.sumOf { it.kcalLogged },
            protein = logs.sumOf { it.proteinGLogged },
            carbs = logs.sumOf { it.carbsGLogged },
            fat = logs.sumOf { it.fatGLogged },
            fiber = logs.sumOf { it.fiberGLogged }
        )
    }

    operator fun times(factor: Int) = MacroSet(kcal * factor, protein * factor, carbs * factor, fat * factor, fiber * factor)
}

/** One row of the weekly breakdown table -- a single day's totals.
 * `isToday`/`isFuture` let the UI style pretracked (future) days and
 * today differently from already-lived days. */
data class DaySummary(
    val date: LocalDate,
    val isToday: Boolean,
    val isFuture: Boolean,
    val totals: MacroSet
)

data class WeeklySummary(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val eaten: MacroSet,
    val goal: MacroSet,
    val days: List<DaySummary> // always 7, Monday..Sunday
)

/** A single food's contribution to one threshold nutrient this week --
 * backs the "top contributors" list under each threshold bar. */
data class FoodContribution(val name: String, val amount: Int)

/** One "don't go over this" nutrient -- unlike the macro bars, there's
 * no "good" amount to hit, just a ceiling not worth crossing, so this
 * only tracks eaten-vs-max plus which foods drove it, rather than a
 * goal to reach. */
data class ThresholdNutrient(
    val label: String,
    val eatenWeekly: Int,
    val maxWeekly: Int,
    val unit: String,
    val topContributors: List<FoodContribution>
)

data class ThresholdsSummary(
    val sodium: ThresholdNutrient,
    val addedSugar: ThresholdNutrient,
    val saturatedFat: ThresholdNutrient
)

// Population-level upper limits, not personalized targets -- same
// source as the (currently unused-by-any-endpoint) physiological_
// guidelines table seeded on the backend: 2025-2030 Dietary Guidelines
// for Americans / WHO. Sodium is a flat daily figure; sugar and
// saturated fat are %-of-kcal, so they scale with your own kcal goal
// rather than being flat grams for everyone.
private const val SODIUM_MAX_MG_PER_DAY = 2300
private const val ADDED_SUGAR_MAX_PCT_OF_KCAL = 0.10
private const val SATURATED_FAT_MAX_PCT_OF_KCAL = 0.10
private const val KCAL_PER_G_SUGAR = 4
private const val KCAL_PER_G_SATURATED_FAT = 9

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(
        val weekly: WeeklySummary,
        val thresholds: ThresholdsSummary,
        val streakDays: Int
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        load()
    }

    fun load() {
        if (_uiState.value !is HomeUiState.Success) {
            _uiState.value = HomeUiState.Loading
        }
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val weekStart = today.with(DayOfWeek.MONDAY)
                val weekEnd = weekStart.plusDays(6)

                // Streak lookback -- 60 days is comfortably more than any
                // realistic streak while staying a cheap query; if you
                // ever hit that ceiling in practice, congrats, and also
                // just bump this number.
                val streakLookbackStart = today.minusDays(60)

                val weekLogsDeferred = ApiClient.service.getLogsInRange(
                    weekStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    weekEnd.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
                val goal = ApiClient.service.getActiveGoal()
                val streakLogs = if (streakLookbackStart < weekStart) {
                    // No overlap with the week query -- fetch the full
                    // separate lookback range.
                    ApiClient.service.getLogsInRange(
                        streakLookbackStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    )
                } else {
                    emptyList()
                }

                val weekLogsByDate = weekLogsDeferred.groupBy { LocalDate.parse(it.date) }

                val dailyGoal = MacroSet(
                    kcal = goal.kcalTarget.toDoubleOrNull()?.toInt() ?: 0,
                    protein = goal.proteinGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    carbs = goal.carbsGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    fat = goal.fatGTarget.toDoubleOrNull()?.toInt() ?: 0,
                    fiber = goal.fiberGTarget.toDoubleOrNull()?.toInt() ?: 0
                )

                val days = (0..6).map { offset ->
                    val date = weekStart.plusDays(offset.toLong())
                    DaySummary(
                        date = date,
                        isToday = date == today,
                        isFuture = date > today,
                        totals = MacroSet.fromLogs(weekLogsByDate[date] ?: emptyList())
                    )
                }

                val weeklyEaten = MacroSet(
                    kcal = days.sumOf { it.totals.kcal },
                    protein = days.sumOf { it.totals.protein },
                    carbs = days.sumOf { it.totals.carbs },
                    fat = days.sumOf { it.totals.fat },
                    fiber = days.sumOf { it.totals.fiber }
                )

                val weekly = WeeklySummary(
                    weekStart = weekStart,
                    weekEnd = weekEnd,
                    eaten = weeklyEaten,
                    goal = dailyGoal * 7,
                    days = days
                )

                val thresholds = buildThresholdsSummary(weekLogsDeferred, dailyGoal.kcal)

                // Streak: distinct dates (up to and including today only --
                // pretracked future days don't count towards "days in a
                // row tracked") that have at least one log, counted
                // backward consecutively. Today not yet being logged
                // doesn't break a streak that's otherwise unbroken through
                // yesterday -- the day isn't over yet.
                val allStreakDates = (streakLogs + weekLogsDeferred.filter { it.date <= today.format(DateTimeFormatter.ISO_LOCAL_DATE) })
                    .map { LocalDate.parse(it.date) }
                    .filter { it <= today }
                    .toSet()

                var streak = 0
                var cursor = if (today in allStreakDates) today else today.minusDays(1)
                while (cursor in allStreakDates) {
                    streak++
                    cursor = cursor.minusDays(1)
                }

                _uiState.value = HomeUiState.Success(weekly, thresholds, streak)
            } catch (e: Exception) {
                // Common causes: no active goal yet (404 from
                // getActiveGoal -- same as the Journal screen), or
                // connectivity issues (same as the old health check).
                _uiState.value = HomeUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Builds the sodium/added-sugar/saturated-fat threshold card from
     * this week's logs -- same log list the weekly macro summary uses,
     * so "this week" means the same date range in both places. Each
     * food (item or recipe name) is summed across the week and the top
     * 3 contributors surfaced, so going over is actionable ("cut back
     * on X") rather than just a number. */
    private fun buildThresholdsSummary(weekLogs: List<Log>, dailyKcalGoal: Int): ThresholdsSummary {
        val weeklyKcalGoal = dailyKcalGoal * 7

        fun topContributors(amountOf: (Log) -> Int): List<FoodContribution> =
            weekLogs
                .groupBy { it.itemName ?: it.recipeName ?: "Unknown" }
                .map { (name, logs) -> FoodContribution(name, logs.sumOf(amountOf)) }
                .filter { it.amount > 0 }
                .sortedByDescending { it.amount }
                .take(3)

        val sodiumEaten = weekLogs.sumOf { it.sodiumMgLogged }
        val sugarEaten = weekLogs.sumOf { it.sugarGLogged }
        val satFatEaten = weekLogs.sumOf { it.saturatedFatGLogged }

        return ThresholdsSummary(
            sodium = ThresholdNutrient(
                label = "Sodium",
                eatenWeekly = sodiumEaten,
                maxWeekly = SODIUM_MAX_MG_PER_DAY * 7,
                unit = "mg",
                topContributors = topContributors { it.sodiumMgLogged }
            ),
            addedSugar = ThresholdNutrient(
                label = "Sugar",
                eatenWeekly = sugarEaten,
                maxWeekly = ((weeklyKcalGoal * ADDED_SUGAR_MAX_PCT_OF_KCAL) / KCAL_PER_G_SUGAR).toInt(),
                unit = "g",
                topContributors = topContributors { it.sugarGLogged }
            ),
            saturatedFat = ThresholdNutrient(
                label = "Saturated fat",
                eatenWeekly = satFatEaten,
                maxWeekly = ((weeklyKcalGoal * SATURATED_FAT_MAX_PCT_OF_KCAL) / KCAL_PER_G_SATURATED_FAT).toInt(),
                unit = "g",
                topContributors = topContributors { it.saturatedFatGLogged }
            )
        )
    }
}