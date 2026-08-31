package com.mealtracker.android.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * There's no dedicated Android system setting for "first day of the
 * week" separate from region/locale -- the OS itself derives it from
 * locale (see e.g. the platform Calendar/ICU implementations), so
 * "take it from the global phone settings" means reading it off the
 * device's default Locale here, same as everything else already does
 * for e.g. month names. Falls back to Locale.getDefault() at call
 * time (not cached) so it stays correct if the person changes their
 * region without restarting the app.
 */
fun localeFirstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

/** Single-letter weekday header row (S M T W T F S, or reordered/
 * relabeled for locales that don't start on Sunday) for calendar
 * grids -- shared so WeekPickerDialog, StreakCalendarDialog, and
 * CalendarPickerDialog can't drift out of sync with each other. */
fun localeWeekdayHeaders(): List<String> {
    val first = localeFirstDayOfWeek()
    return (0..6).map { offset ->
        first.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
}

/** How many empty cells a month grid needs before day 1, given it
 * starts the week on localeFirstDayOfWeek() rather than being
 * hardcoded to Sunday or Monday. */
fun localeStartOffset(firstOfMonth: LocalDate): Int {
    val first = localeFirstDayOfWeek()
    return (firstOfMonth.dayOfWeek.value - first.value + 7) % 7
}

/** Walks back to the first day of this date's locale week -- the
 * locale-aware equivalent of the old `date.with(DayOfWeek.MONDAY)`. */
fun LocalDate.startOfLocaleWeek(): LocalDate {
    val first = localeFirstDayOfWeek()
    var date = this
    while (date.dayOfWeek != first) {
        date = date.minusDays(1)
    }
    return date
}