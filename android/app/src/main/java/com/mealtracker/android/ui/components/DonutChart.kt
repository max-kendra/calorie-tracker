package com.mealtracker.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Simple ring/donut chart -- draws one arc per segment, proportional to
 * its fraction of the total, in the given color. Used on the
 * Macronutrients and Meal Calorie Goal screens to visualize the current
 * split, matching the design doc's mockup layout (colored donut with the
 * running total shown in the center).
 *
 * `segments` should be (fraction 0f..1f, color) pairs. Fractions don't
 * need to sum to exactly 1f -- if they sum to less, the remainder is
 * simply left as empty track, which is useful for showing an
 * incomplete/invalid split visually (e.g. only 92% allocated).
 */
@Composable
fun DonutChart(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    diameter: Dp = 160.dp,
    strokeWidth: Dp = 22.dp,
    trackColor: Color = Color(0xFFE0E0E0),
    centerContent: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val strokePx = strokeWidth.toPx()
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)

            // Background track for the "unallocated" portion.
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx)
            )

            var startAngle = -90f
            for ((fraction, color) in segments) {
                val sweep = fraction.coerceIn(0f, 1f) * 360f
                if (sweep <= 0f) continue
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx)
                )
                startAngle += sweep
            }
        }
        centerContent()
    }
}
