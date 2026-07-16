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
    primary = TealPrimaryDark,
    background = TealBackgroundDark,
    surface = TealSurfaceDark,
    surfaceVariant = TealSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    primaryContainer = TealPrimaryContainer,
    // Screen background -- soft teal. Cards default to `surface` (below),
    // which is plain white, so any Card()/Scaffold() using the theme's
    // colors automatically gets "white card on teal background" with no
    // per-screen styling needed.
    background = TealBackground,
    surface = Color.White,
    surfaceVariant = TealSurfaceVariant
)

@Composable
fun MealTrackerTheme(
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