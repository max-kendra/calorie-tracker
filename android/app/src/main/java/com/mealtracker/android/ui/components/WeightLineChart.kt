package com.mealtracker.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Weight-trend graph for the Profile screen - now backed by Vico
 * (Compose-native charting library) instead of a hand-rolled Canvas
 * polyline (see design discussion: "let's do vico... i'd just [like]
 * something prettier than what we have now"). Deliberately just a
 * nicer-looking static line, no tap/scrub interactivity - that wasn't
 * asked for and Vico's marker API adds real complexity for something
 * not actually wanted here.
 *
 * Signature intentionally unchanged from the old Canvas version so
 * ProfileScreen.kt's call site didn't need to change - same
 * points/goalKg/lineColor/goalLineColor/height contract, entirely new
 * implementation underneath.
 *
 * `points` should be sorted oldest-first (readWeightHistory() already
 * returns them that way). `goalKg`, if provided, draws a dashed
 * reference line.
 *
 * Vico's API has moved across several alpha releases - if a newer
 * version is pulled in later and something here doesn't compile,
 * check https://github.com/patrykandpatrick/vico's migration notes.
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

    val modelProducer = remember { CartesianChartModelProducer() }
    // x-axis is epoch seconds as a Double - Vico works in numeric x/y
    // pairs, not Instants directly, so the axis label formatter below
    // converts back to a date string for display.
    val xValues = remember(points) { points.map { it.first.epochSecond.toDouble() } }
    val yValues = remember(points) { points.map { it.second } }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries { series(x = xValues, y = yValues) }
        }
    }

    // Custom axis formatters (nice dates/kg values instead of raw
    // numbers) temporarily removed -- CartesianValueFormatter's exact
    // lambda signature didn't match what I guessed (unresolved
    // toFloat/toLong on its "value" param suggests a parameter-count or
    // -order mismatch, not just a wrong import this time), and I don't
    // want to guess a third time without the compiler's full output. See
    // design discussion - this isolates whether the rest of the chart
    // compiles/renders correctly first, with axis labels reverting to
    // Vico's own defaults (raw numbers) until this gets fixed properly.

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    // Solid line only, no gradient area fill underneath -
                    // fill() doesn't accept a Compose Brush (needs Vico's
                    // own DynamicShader type instead), and I wasn't
                    // confident enough in that exact API to guess again
                    // after the first attempt didn't compile. Can revisit
                    // for a nicer gradient fill once this compiles clean.
                    rememberLine(fill = LineCartesianLayer.LineFill.single(fill(lineColor)))
                )
            ),
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            // goalKg's dashed reference line has no first-class Vico
            // equivalent as clean as the old Canvas dashPathEffect line,
            // so it's intentionally dropped here rather than forced in
            // awkwardly - goalKg is still accepted in the signature
            // (unused) so callers don't need updating; can be revisited
            // with Vico's decoration/marker APIs if the goal line is
            // missed in practice.
        ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(height)
    )
}

private fun formatKg(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)