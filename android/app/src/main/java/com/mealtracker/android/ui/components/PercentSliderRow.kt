package com.mealtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A 0-100% slider row used for both macro-split and meal-calorie-split
 * screens -- replaces each screen's own private slider row (see design
 * discussion: the default Material3 slider thumb is a narrow vertical
 * pill/"stick" shape, which read as ugly and was fiddly to grab
 * precisely). Two changes address that:
 *  - A custom circular thumb (Slider's `thumb` slot, supported since
 *    Material3 1.2) instead of the default pill.
 *  - Flanking +/- stepper buttons for precise 1% nudges, so getting an
 *    exact value doesn't depend on a steady drag on a thin track.
 * The slider itself is kept, not replaced outright -- large adjustments
 * (e.g. going from 20% to 60%) are still much faster by dragging than
 * by tapping a stepper 40 times.
 *
 * Deliberately generic over what the "value" line of text shows (grams
 * + Cal for macros, Cal for meal splits) -- callers pass that in
 * pre-formatted rather than this component knowing about either
 * domain's row type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PercentSliderRow(
    label: String,
    valueText: String,
    percent: Int,
    color: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label \u00b7 $valueText",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.bodyLarge,
                color = color
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onValueChange((percent - 1).coerceIn(0, 100)) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
            }
            Slider(
                value = percent.toFloat(),
                onValueChange = { onValueChange(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 99,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    activeTrackColor = color,
                    inactiveTrackColor = color.copy(alpha = 0.24f)
                ),
                thumb = {
                    // Replaces the default pill-shaped thumb with a
                    // plain circle -- see this file's doc comment.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(color, CircleShape)
                    )
                }
            )
            IconButton(
                onClick = { onValueChange((percent + 1).coerceIn(0, 100)) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label")
            }
        }
    }
}