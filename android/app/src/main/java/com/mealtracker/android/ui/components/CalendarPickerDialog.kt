package com.mealtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mealtracker.android.ui.screens.CalendarMonthState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Coloring scheme: how much of the day got tracked, by distinct meal
// types logged (0-4) -- see CalendarMonthState's doc comment for why
// this counts distinct meal TYPES, not raw log count.
private val TrackedNone = Color(0xFFBDBDBD)   // 0 meal types -- gray
private val TrackedOne = Color(0xFFE57373)    // 1 meal type -- red
private val TrackedTwo = Color(0xFFFFC107)    // 2 meal types -- yellow
private val TrackedThreePlus = Color(0xFF4CAF50) // 3+ meal types -- green

private fun colorForCount(count: Int): Color = when {
    count >= 3 -> TrackedThreePlus
    count == 2 -> TrackedTwo
    count == 1 -> TrackedOne
    else -> TrackedNone
}

private val WEEKDAY_HEADERS = listOf("S", "M", "T", "W", "T", "F", "S")

/**
 * Month calendar for picking a Journal date. Each day is a colored
 * circle (see colorForCount) reflecting how many distinct meal types
 * were logged that day -- lets you spot gaps in tracking at a glance,
 * not just navigate dates. `monthState` is nullable/loading-aware since
 * the coloring data loads async per month (see
 * JournalViewModel.loadCalendarMonth) -- days render as untracked/gray
 * until that resolves, rather than blocking the picker from opening.
 */
@Composable
fun CalendarPickerDialog(
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    monthState: CalendarMonthState?,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onMonthChange(displayedMonth.minusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        "${displayedMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${displayedMonth.year}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { onMonthChange(displayedMonth.plusMonths(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    WEEKDAY_HEADERS.forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val firstOfMonth = displayedMonth.atDay(1)
                val daysInMonth = displayedMonth.lengthOfMonth()
                // DayOfWeek.value is Monday=1..Sunday=7; %7 remaps to
                // Sunday=0..Saturday=6 so the grid starts on Sunday,
                // matching WEEKDAY_HEADERS above.
                val startOffset = firstOfMonth.dayOfWeek.value % 7
                val totalCells = startOffset + daysInMonth
                val rowCount = (totalCells + 6) / 7

                for (row in 0 until rowCount) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0..6) {
                            val cellIndex = row * 7 + col
                            val dayNum = cellIndex - startOffset + 1
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (dayNum in 1..daysInMonth) {
                                    val date = displayedMonth.atDay(dayNum)
                                    val count = monthState?.mealTypesLoggedByDay?.get(date) ?: 0
                                    val isSelected = date == selectedDate
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(colorForCount(count).copy(alpha = if (isSelected) 1f else 0.6f))
                                            .then(
                                                if (isSelected) {
                                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                } else Modifier
                                            )
                                            .clickable { onDateSelected(date) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "$dayNum",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

                // Legend -- explains the coloring without needing a
                // separate help screen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LegendDot(TrackedThreePlus, "3+")
                    LegendDot(TrackedTwo, "2")
                    LegendDot(TrackedOne, "1")
                    LegendDot(TrackedNone, "0")
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            " $label meals",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, end = 8.dp)
        )
    }
}