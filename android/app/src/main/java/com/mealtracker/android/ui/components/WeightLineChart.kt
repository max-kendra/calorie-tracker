package com.mealtracker.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Simple line chart for the Profile screen's weight-trend graph --
 * hand-rolled Canvas drawing, same approach as DonutChart, rather than
 * pulling in a charting library for what's fundamentally one polyline.
 *
 * `points` should be sorted oldest-first (readWeightHistory() already
 * returns them that way). `goalKg`, if provided, draws a dashed
 * reference line, matching the "goal weight" line shown in the design
 * inspiration screenshots.
 */
@Composable
fun WeightLineChart(
    points: List<Pair<Instant, Double>>,
    modifier: Modifier = Modifier,
    goalKg: Double? = null,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    goalLineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    height: androidx.compose.ui.unit.Dp = 220.dp
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No weight entries in this range",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val minTime = points.first().first.epochSecond.toFloat()
    val maxTime = points.last().first.epochSecond.toFloat()
    val timeSpan = (maxTime - minTime).coerceAtLeast(1f)

    val allValues = points.map { it.second } + listOfNotNull(goalKg)
    // Padding above/below the data range so the line/dots/goal-line
    // don't touch the very top or bottom edge of the chart.
    val rawMin = allValues.min()
    val rawMax = allValues.max()
    val padding = ((rawMax - rawMin) * 0.15).coerceAtLeast(1.0)
    val minValue = (rawMin - padding).toFloat()
    val maxValue = (rawMax + padding).toFloat()
    val valueSpan = (maxValue - minValue).coerceAtLeast(0.01f)

    val dateFormatter = remember(points) { DateTimeFormatter.ofPattern("dd/MM") }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            fun xFor(time: Instant) = (time.epochSecond - minTime) / timeSpan * size.width
            fun yFor(value: Double) = size.height - ((value.toFloat() - minValue) / valueSpan * size.height)

            if (goalKg != null) {
                val y = yFor(goalKg)
                drawLine(
                    color = goalLineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )
            }

            for (i in 0 until points.size - 1) {
                val (t1, v1) = points[i]
                val (t2, v2) = points[i + 1]
                drawLine(
                    color = lineColor,
                    start = Offset(xFor(t1), yFor(v1)),
                    end = Offset(xFor(t2), yFor(v2)),
                    strokeWidth = 5f
                )
            }

            for ((time, value) in points) {
                drawCircle(
                    color = lineColor,
                    radius = 6f,
                    center = Offset(xFor(time), yFor(value))
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                dateFormatter.format(points.first().first.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            Text(
                dateFormatter.format(points.last().first.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}