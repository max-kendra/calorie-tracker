package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.PhysiologicalGuideline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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
    val topContributors: List<FoodContribution>,
    // Where the maxWeekly figure actually comes from -- backs the info
    // tooltip on the threshold row. Sourced from the backend's
    // physiological_guidelines table when available (see
    // buildThresholdsSummary), falling back to a hardcoded equivalent
    // if that fetch failed, so the row still has SOME explanation
    // rather than going blank.
    val basis: String
)

data class ThresholdsSummary(
    val sodium: ThresholdNutrient,
    val addedSugar: ThresholdNutrient,
    val saturatedFat: ThresholdNutrient
)

/** Backs the streak calendar dialog -- which dates in the displayed
 * month have at least one log (shown with an orange ring), loaded one
 * month at a time the same way JournalViewModel's CalendarMonthState
 * works for the Journal date picker. */
data class StreakCalendarMonthState(
    val yearMonth: YearMonth,
    val isLoading: Boolean = true,
    val loggedDates: Set<LocalDate> = emptySet()
)

// Fallback values ONLY -- used if GET /guidelines fails (e.g. transient
// network hiccup) so the threshold card degrades gracefully instead of
// going blank. When the fetch succeeds, the actual physiological_
// guidelines row (2025-2030 Dietary Guidelines for Americans / WHO,
// same source as these fallbacks) is used instead -- see
// buildThresholdsSummary. Sodium is a flat daily figure; sugar and
// saturated fat are %-of-kcal, so they scale with your own kcal goal
// rather than being flat grams for everyone.
private const val FALLBACK_SODIUM_MAX_MG_PER_DAY = 2300
private const val FALLBACK_ADDED_SUGAR_MAX_PCT_OF_KCAL = 0.10
private const val FALLBACK_SATURATED_FAT_MAX_PCT_OF_KCAL = 0.10
private const val KCAL_PER_G_SUGAR = 4
private const val KCAL_PER_G_SATURATED_FAT = 9

private const val FALLBACK_SODIUM_BASIS =
    "2025-2030 Dietary Guidelines for Americans: keep sodium under 2,300mg/day. WHO recommends under 2,000mg/day."
private const val FALLBACK_ADDED_SUGAR_BASIS =
    "2025-2030 Dietary Guidelines for Americans: keep added sugar under 10% of daily calories. WHO's stricter guidance suggests under 5% for extra benefit."
private const val FALLBACK_SATURATED_FAT_BASIS =
    "2025-2030 Dietary Guidelines for Americans: keep saturated fat under 10% of daily calories, consistent with WHO guidance."

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

    private val _streakCalendarState = MutableStateFlow<StreakCalendarMonthState?>(null)
    val streakCalendarState: StateFlow<StreakCalendarMonthState?> = _streakCalendarState

    init {
        load()
    }

    /** Loads the week CONTAINING [referenceDate] -- defaults to today,
     * but the week nav arrows / week picker pass an arbitrary date to
     * jump to a different week. Streak/today/future-day logic below
     * always uses the ACTUAL today, regardless of which week is being
     * viewed -- viewing a past or future week shouldn't change what
     * "today" or the streak means. */
    fun load(referenceDate: LocalDate = LocalDate.now()) {
        if (_uiState.value !is HomeUiState.Success) {
            _uiState.value = HomeUiState.Loading
        }
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val weekStart = referenceDate.with(DayOfWeek.MONDAY)
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
                // Streak always looks back from the REAL today, entirely
                // independent of weekStart/weekEnd above -- those move
                // when the user browses to a different week via the
                // week picker, but the streak shouldn't change just
                // because you're looking at last month.
                val streakLogs = ApiClient.service.getLogsInRange(
                    streakLookbackStart.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )

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

                val thresholds = buildThresholdsSummary(weekLogsDeferred, dailyGoal.kcal, fetchGuidelinesOrEmpty())

                // Streak: distinct dates (streakLogs already covers up to
                // and including today only -- pretracked future days in
                // whichever week is being viewed don't factor in here)
                // that have at least one log, counted backward
                // consecutively. Today not yet being logged doesn't break
                // a streak that's otherwise unbroken through yesterday --
                // the day isn't over yet.
                val allStreakDates = streakLogs.map { LocalDate.parse(it.date) }.toSet()

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

    /** GET /guidelines is supplementary (explains the threshold numbers,
     * doesn't gate anything) -- failure here shouldn't take down the
     * whole Home screen the way a failed goal/logs fetch does, so it
     * gets its own try/catch rather than sharing load()'s, falling back
     * to an empty list (buildThresholdsSummary then uses the hardcoded
     * FALLBACK_* constants instead). */
    private suspend fun fetchGuidelinesOrEmpty(): List<PhysiologicalGuideline> =
        try {
            ApiClient.service.getGuidelines()
        } catch (e: Exception) {
            emptyList()
        }

    /** Builds the sodium/added-sugar/saturated-fat threshold card from
     * this week's logs -- same log list the weekly macro summary uses,
     * so "this week" means the same date range in both places. Each
     * food (item or recipe name) is summed across the week and the top
     * 3 contributors surfaced, so going over is actionable ("cut back
     * on X") rather than just a number.
     *
     * `guidelines` supplies both the actual ceiling values AND the
     * explanatory text behind them (see PhysiologicalGuideline) -- one
     * source for both, rather than the max-value math and the tooltip
     * text risking drifting out of sync with each other. */
    private fun buildThresholdsSummary(
        weekLogs: List<Log>,
        dailyKcalGoal: Int,
        guidelines: List<PhysiologicalGuideline>
    ): ThresholdsSummary {
        val weeklyKcalGoal = dailyKcalGoal * 7
        val guidelinesByName = guidelines.associateBy { it.name }
        val sodiumGuideline = guidelinesByName["sodium_mg_per_day"]
        val sugarGuideline = guidelinesByName["added_sugar_pct_of_kcal"]
        val saturatedFatGuideline = guidelinesByName["saturated_fat_pct_of_kcal"]

        fun topContributors(amountOf: (Log) -> Int): List<FoodContribution> =
            weekLogs
                .groupBy { it.itemName ?: it.recipeName ?: "Unknown" }
                .map { (name, logs) -> FoodContribution(name, logs.sumOf(amountOf)) }
                .filter { it.amount > 0 }
                .sortedByDescending { it.amount }
                .take(3)

        val sodiumEaten = weekLogs.sumOf { it.sodiumMgLogged }
        // Uses countableSugarGLogged, not sugarGLogged -- excludes raw
        // USDA-import-origin ingredients (e.g. a banana) from this
        // total, since added-sugar dietary guidance targets added/free
        // sugars specifically, not sugar naturally occurring in whole
        // foods (see design discussion: "my highest sugar source is
        // freaking bananas... i'm not sure we should be counting
        // that"). Sodium and saturated fat deliberately stay as raw
        // totals above/below -- that guidance is about TOTAL intake
        // regardless of source, a raw ingredient's natural sodium/fat
        // counts the same as a packaged food's.
        val sugarEaten = weekLogs.sumOf { it.countableSugarGLogged }
        val satFatEaten = weekLogs.sumOf { it.saturatedFatGLogged }

        val sodiumMaxPerDay = sodiumGuideline?.maxValue?.toInt() ?: FALLBACK_SODIUM_MAX_MG_PER_DAY
        // Guideline max_value for these two is a percentage number (e.g.
        // "10" meaning 10%), not a fraction -- divide by 100 to match
        // the FALLBACK_*_PCT_OF_KCAL constants' fraction form (0.10).
        val sugarMaxPctOfKcal = (sugarGuideline?.maxValue?.let { it / 100.0 }) ?: FALLBACK_ADDED_SUGAR_MAX_PCT_OF_KCAL
        val satFatMaxPctOfKcal = (saturatedFatGuideline?.maxValue?.let { it / 100.0 }) ?: FALLBACK_SATURATED_FAT_MAX_PCT_OF_KCAL

        return ThresholdsSummary(
            sodium = ThresholdNutrient(
                label = "Sodium",
                eatenWeekly = sodiumEaten,
                maxWeekly = sodiumMaxPerDay * 7,
                unit = "mg",
                topContributors = topContributors { it.sodiumMgLogged },
                basis = sodiumGuideline?.basis ?: FALLBACK_SODIUM_BASIS
            ),
            addedSugar = ThresholdNutrient(
                label = "Sugar",
                eatenWeekly = sugarEaten,
                maxWeekly = ((weeklyKcalGoal * sugarMaxPctOfKcal) / KCAL_PER_G_SUGAR).toInt(),
                unit = "g",
                topContributors = topContributors { it.countableSugarGLogged },
                basis = sugarGuideline?.basis ?: FALLBACK_ADDED_SUGAR_BASIS
            ),
            saturatedFat = ThresholdNutrient(
                label = "Saturated fat",
                eatenWeekly = satFatEaten,
                maxWeekly = ((weeklyKcalGoal * satFatMaxPctOfKcal) / KCAL_PER_G_SATURATED_FAT).toInt(),
                unit = "g",
                topContributors = topContributors { it.saturatedFatGLogged },
                basis = saturatedFatGuideline?.basis ?: FALLBACK_SATURATED_FAT_BASIS
            )
        )
    }

    /** Loads (or reloads) which dates in one month have at least one log
     * -- backs the streak calendar dialog's orange rings. Same one-
     * month-at-a-time pattern as JournalViewModel.loadCalendarMonth,
     * called when the dialog opens and again on month navigation. */
    fun loadStreakCalendarMonth(yearMonth: YearMonth) {
        _streakCalendarState.value = StreakCalendarMonthState(yearMonth, isLoading = true)
        viewModelScope.launch {
            try {
                val start = yearMonth.atDay(1)
                val end = yearMonth.atEndOfMonth()
                val logs = ApiClient.service.getLogsInRange(
                    start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                )
                val loggedDates = logs.map { LocalDate.parse(it.date) }.toSet()
                _streakCalendarState.value = StreakCalendarMonthState(yearMonth, isLoading = false, loggedDates = loggedDates)
            } catch (e: Exception) {
                // Same as Journal's calendar -- a failed month load just
                // shows an empty (no rings) month rather than blocking
                // the dialog from opening at all.
                _streakCalendarState.value = StreakCalendarMonthState(yearMonth, isLoading = false)
            }
        }
    }

    fun clearStreakCalendarState() {
        _streakCalendarState.value = null
    }
}