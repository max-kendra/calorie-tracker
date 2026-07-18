package com.mealtracker.android.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * Best-effort status bar tinting - sets `window.statusBarColor` for as
 * long as this is composed, restoring whatever it was before on
 * dispose (so leaving the screen doesn't leave other screens tinted).
 *
 * KNOWN LIMITATION: this uses the classic (deprecated but still
 * functional on most currently-shipping Android versions)
 * `Window.statusBarColor` API. On Android 15+, the platform increasingly
 * enforces true edge-to-edge and may ignore this - the fully correct
 * fix there is to stop consuming the top system-bar inset in this
 * screen and let its own background Composable paint through into that
 * area instead. Not done here to keep this change self-contained;
 * revisit if this stops having a visible effect on newer OS versions.
 */
@Composable
fun StatusBarColor(color: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return

    DisposableEffect(color) {
        val previous = window.statusBarColor
        window.statusBarColor = color.toArgb()
        onDispose {
            window.statusBarColor = previous
        }
    }
}