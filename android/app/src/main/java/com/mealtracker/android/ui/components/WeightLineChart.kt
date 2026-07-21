package com.mealtracker.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Weight-trend graph for the Profile screen. Back to a hand-rolled
 * Canvas implementation after Vico turned into repeated dependency/API
 * dead ends across several attempts (see design discussion: "yeah,
 * none of this got fixed... i guess we can go back to the material
 * charts and just format them to look a little nicer" -- this is that:
 * same underlying approach as before Vico, but with a smooth curve
 * instead of straight segments, a gradient fill under the line, and a
 * properly padded Y-axis range instead of one that forces 0 as the
 * floor). Everything here is first-party androidx.compose.ui drawing
 * API -- Canvas, Path, Stroke, Brush, and the platform Paint/Canvas for
 * axis labels -- nothing from a third-party charting library, so
 * there's no version/API-surface risk left to chase.
 *
 * `points` should be sorted oldest-first (readWeightHistory() already
 * returns them that way). `goalKg`, if provided, draws a dashed
 * reference line at that value.
 */
@Composable
fun WeightLineChart(
    points: List<Pair<Instant, Double>>,
    modifier: Modifier = Modifier,
    goalKg: Double? = null,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    goalLineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    height: Dp = 220.dp
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

    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    // Read here, in the composable body, not inside the Canvas draw
    // lambda below -- that lambda runs during the draw phase, not
    // composition, so @Composable-readable properties like
    // MaterialTheme.colorScheme can't be accessed there directly (same
    // reasoning as labelColor/gridColor already being extracted here).
    val surfaceColor = MaterialTheme.colorScheme.surface
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd/MM") }

    // Calculate Y-bounds strictly based on logged weights
    val weightMin = remember(points) { points.minOf { it.second } }
    val weightMax = remember(points) { points.maxOf { it.second } }

    // Calculate range and 15% padding from actual weight logs
    val weightRange = remember(weightMin, weightMax) { (weightMax - weightMin).coerceAtLeast(1.0) }
    val yPadding = remember(weightRange) { weightRange * 0.15 }

    // Clamp yMin so a far-away goal line doesn't compress the chart completely
    val yMin = remember(weightMin, goalKg, yPadding, weightRange) {
        val rawMin = weightMin - yPadding
        if (goalKg != null) {
            // Only extend yMin down to goalKg if it's within 1.5x of the current weight range
            rawMin.coerceAtMost(goalKg - yPadding).coerceAtLeast(weightMin - weightRange * 1.5)
        } else {
            rawMin
        }
    }
    val yMax = remember(weightMax, yPadding) { weightMax + yPadding }
    val xMinEpoch = remember(points) { points.first().first.epochSecond }
    val xMaxEpoch = remember(points) { points.last().first.epochSecond }
    val xSpan = remember(xMinEpoch, xMaxEpoch) { (xMaxEpoch - xMinEpoch).coerceAtLeast(1) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 44.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)
    ) {
        val chartWidth = size.width
        val chartHeight = size.height

        fun xToPixel(epochSecond: Long): Float =
            ((epochSecond - xMinEpoch).toFloat() / xSpan.toFloat()) * chartWidth

        fun yToPixel(value: Double): Float {
            val fraction = ((value - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
            return chartHeight - (fraction * chartHeight).toFloat()
        }

        // Horizontal gridlines with kg labels - 4 evenly spaced
        // steps between yMin and yMax.
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val value = yMin + (yMax - yMin) * (i.toDouble() / gridSteps)
            val y = yToPixel(value)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1.dp.toPx()
            )
            drawIntoCanvas { canvas ->
                val paint = Paint().asFrameworkPaint().apply {
                    color = labelColor.toArgb()
                    textSize = 11.sp.toPx()
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
                canvas.nativeCanvas.drawText(
                    formatKg(value),
                    -6.dp.toPx(),
                    y + (paint.textSize / 3f),
                    paint
                )
            }
        }

        // Goal reference line, dashed - drawn before the data line
        // so the data line renders on top of it where they cross.
        if (goalKg != null) {
            val goalY = yToPixel(goalKg)
            drawLine(
                color = goalLineColor,
                start = Offset(0f, goalY),
                end = Offset(chartWidth, goalY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
        }

        // Smooth curve through the data points, using quadratic
        // Bezier segments between each pair's midpoint - a simple,
        // well-understood technique for turning a polyline into a
        // smooth curve without needing a full spline library.
        val pixelPoints = points.map { (instant, value) ->
            Offset(xToPixel(instant.epochSecond), yToPixel(value))
        }

        val linePath = Path()
        if (pixelPoints.size == 1) {
            val p = pixelPoints.first()
            linePath.moveTo(p.x, p.y)
            linePath.lineTo(p.x, p.y)
        } else {
            linePath.moveTo(pixelPoints.first().x, pixelPoints.first().y)
            for (i in 0 until pixelPoints.size - 1) {
                val current = pixelPoints[i]
                val next = pixelPoints[i + 1]
                val midX = (current.x + next.x) / 2f
                val midY = (current.y + next.y) / 2f
                linePath.quadraticTo(current.x, current.y, midX, midY)
            }
            val last = pixelPoints.last()
            linePath.lineTo(last.x, last.y)
        }

        // Gradient fill under the curve, fading to transparent -
        // built from the line path plus a closing run down to the
        // bottom and back to the start.
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(pixelPoints.last().x, chartHeight)
            lineTo(pixelPoints.first().x, chartHeight)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
                startY = 0f,
                endY = chartHeight
            )
        )

        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // A small dot at each actual data point, so individual
        // entries stay legible even with the curve smoothing.
        pixelPoints.forEach { point ->
            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = point)
            drawCircle(color = surfaceColor, radius = 1.3.dp.toPx(), center = point)
        }

        // X-axis date labels - first, middle, and last point only,
        // to avoid crowding on narrow screens.
        val labelIndices = if (points.size <= 2) {
            points.indices.toList()
        } else {
            listOf(0, points.size / 2, points.size - 1)
        }
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                color = labelColor.toArgb()
                textSize = 11.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            labelIndices.forEach { index ->
                val (instant, _) = points[index]
                val label = dateFormatter.format(instant.atZone(ZoneId.systemDefault()))
                canvas.nativeCanvas.drawText(
                    label,
                    xToPixel(instant.epochSecond),
                    chartHeight + 18.dp.toPx(),
                    paint
                )
            }
        }
    }
}

private fun formatKg(value: Double): String {
    val rounded = (value * 10).let { kotlin.math.round(it) } / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}"
    } else {
        String.format(Locale.getDefault(), "%.1f", rounded)
    }
}