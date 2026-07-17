package com.mealtracker.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    background = TealBackgroundDark,
    surface = TealSurfaceDark,
    surfaceVariant = TealSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    primaryContainer = TealPrimaryContainer,
    // Plain white/near-white app-wide -- any per-screen color (like
    // Journal's pastel hero) is applied locally by that screen instead
    // of being baked into the theme (see design discussion).
    background = AppBackground,
    surface = Color.White,
    surfaceVariant = TealSurfaceVariant
)

@Composable
fun MealTrackerTheme(
    // Deliberately does NOT default to isSystemInDarkTheme() right now.
    // DarkColorScheme below exists but only sets primary/background/
    // surface/surfaceVariant -- every screen we've built (Journal's
    // pastel hero, Meal Detail's hero, card whites, chart colors, meal
    // icon tints) paints hardcoded light colors directly rather than
    // reading them from the theme, while Text/Icon DO pick up
    // Material3's dark-mode default (light) "on" colors automatically.
    // Net effect on a dark-mode device: light text on hardcoded light
    // backgrounds -- barely legible, not a real adapted dark theme (see
    // design discussion, this was reported as "dark mode is hella
    // messed up"). Forcing light mode here is the honest fix until
    // those screens are actually redesigned with dark-mode-aware
    // colors -- that's real, separate work, not done as part of this fix.
    darkTheme: Boolean = false,
    // Dynamic color (Material You) is available Android 12+ -- uses
    // colors derived from the user's wallpaper. Off by default here so
    // the look is consistent and predictable; flip to true if you want
    // that effect instead.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}