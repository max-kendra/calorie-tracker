package com.mealtracker.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    // Brighter than light mode's NavyPrimary, deliberately -- see
    // NavyPrimaryDark's own doc comment in Color.kt for why this
    // specifically needed its own dark-mode value (colorScheme.primary
    // doubles as on-background TEXT/icon color in several places, not
    // just a button fill, and dark navy text is illegible on a dark
    // background).
    primary = NavyPrimaryDark,
    // Material3's darkColorScheme() picks its own default onPrimary
    // assuming a typical M3 dark theme's primary is a LIGHT/pastel tone
    // (with a dark, low-contrast "onPrimary" for text on top of it).
    // Since our primary is a dark, saturated navy in both themes, that
    // default onPrimary would be some dark tone with poor contrast on
    // top of it. White is correct here regardless of theme, so it's
    // set explicitly rather than relying on M3's per-theme default.
    onPrimary = Color.White,
    primaryContainer = JournalHeroPastelDark,
    // Light content on the dark navy primaryContainer pill (e.g. the
    // bottom nav's selected-tab indicator) -- explicit for the same
    // reason onPrimary is above: M3's own default assumes a different
    // relationship between primary and primaryContainer than the one
    // this app actually has.
    onPrimaryContainer = Color.White,
    background = AppBackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = Color.White,
    primaryContainer = JournalHeroPastel,
    // Dark navy content on the pastel-blue primaryContainer pill (e.g.
    // the bottom nav's selected-tab indicator) -- same reasoning as
    // DarkColorScheme's own onPrimaryContainer above.
    onPrimaryContainer = NavyPrimary,
    // Plain white/near-white app-wide -- any per-screen color (like
    // Journal's pastel hero) is applied locally by that screen instead
    // of being baked into the theme (see design discussion).
    background = AppBackground,
    surface = Color.White,
    surfaceVariant = NeutralSurfaceVariant
)

/**
 * The theme actually being rendered right now -- NOT the device's
 * system setting. Every place in the app with a hardcoded light/dark
 * color pair (MealVisuals, CatalogVisuals, JournalScreen's hero) needs
 * this, not `isSystemInDarkTheme()` directly: once ThemePreference lets
 * someone pin the app to Light or Dark independent of their system
 * setting (see SettingsScreen), a raw `isSystemInDarkTheme()` call
 * anywhere else in the app quietly goes back to following the device
 * instead of the app's actual current theme -- which was exactly the
 * bug being fixed here (dark-mode colors showing up while the app was
 * visibly in light mode, and vice versa). MealTrackerTheme below is the
 * ONLY place that should ever call `isSystemInDarkTheme()` for the
 * SYSTEM case; everything else should read this instead.
 */
val LocalIsAppDarkTheme = staticCompositionLocalOf { false }

@Composable
fun MealTrackerTheme(
    // Now genuinely follows the system setting -- see this function's
    // git history/design discussion for why it was hardcoded to false
    // for a while (every screen was painting hardcoded Color.White for
    // cards, which read as barely-legible light-text-on-light-
    // background in dark mode). That's fixed now: cards read
    // MaterialTheme.colorScheme.surface instead of a literal
    // Color.White, and the bottom nav bar does too -- so dark mode is a
    // real, coherent theme now, not a half-applied one.
    darkTheme: Boolean = isSystemInDarkTheme(),
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

    CompositionLocalProvider(LocalIsAppDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}