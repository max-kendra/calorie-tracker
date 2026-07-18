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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    // Deliberately the SAME TealPrimary as light mode, not a muted
    // variant -- per design discussion, colored/branded elements are
    // meant to stay exactly as they are; only neutral surfaces/text
    // invert. Same reasoning applies to every other hardcoded color in
    // the app (JournalHeroPastel, MacroColors, MealVisuals meal-icon
    // tints, etc.) -- none of those read from the theme at all, by
    // design, so they don't need a dark-mode counterpart here.
    primary = TealPrimary,
    // Material3's darkColorScheme() picks its own default onPrimary
    // assuming a typical M3 dark theme's primary is a LIGHT/pastel tone
    // (with a dark, low-contrast "onPrimary" for text on top of it).
    // Since our primary is deliberately the same dark, saturated teal in
    // both themes (see above), that default onPrimary was some dark
    // navy-ish tone -- navy text/icons on a dark green button, exactly
    // the "content inside them is navy" bug from design discussion.
    // White is correct here regardless of theme, so it's set explicitly
    // rather than relying on M3's per-theme default.
    onPrimary = Color.White,
    primaryContainer = TealPrimaryContainer,
    background = AppBackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}