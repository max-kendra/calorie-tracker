package com.mealtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAY_HEADERS = listOf("S", "M", "T", "W", "T", "F", "S")

/**
 * Month calendar for picking a WEEK (not a single day) -- backs the
 * Home screen's week navigator. Unlike CalendarPickerDialog (which
 * selects one Journal date and colors days by how much got tracked),
 * clicking any day here selects the Monday-Sunday week that contains
 * it, and every day belonging to the currently-selected week gets
 * highlighted together, not just the one tapped.
 */
@Composable
fun WeekPickerDialog(
    displayedMonth: YearMonth,
    selectedWeekStart: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onWeekSelected: (weekStart: LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedWeekEnd = selectedWeekStart.plusDays(6)
    val today = LocalDate.now()

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
                                    val isInSelectedWeek = date >= selectedWeekStart && date <= selectedWeekEnd
                                    val isToday = date == today
                                    Box(
                                        modifier = Modifier
                                            .padding(1.dp)
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isInSelectedWeek) MaterialTheme.colorScheme.primaryContainer
                                                else androidx.compose.ui.graphics.Color.Transparent
                                            )
                                            .clickable { onWeekSelected(date.with(DayOfWeek.MONDAY)) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "$dayNum",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isInSelectedWeek) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

                Text(
                    "Tap any day to jump to its week",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}