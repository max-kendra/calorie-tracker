package com.mealtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

// Teal palette -- background is a soft, desaturated teal; card surfaces
// are plain white on top of it (see Theme.kt's colorScheme: background
// vs surface). Deliberately app-wide (set on the ColorScheme, not
// per-screen), so this applies consistently everywhere Cards/Scaffolds
// already rely on MaterialTheme.colorScheme.background/surface rather
// than a hardcoded color.
val TealPrimary = Color(0xFF2C6E63)
val TealPrimaryContainer = Color(0xFFB6D9D2)
val TealBackground = Color(0xFFDCE9E5)
val TealSurfaceVariant = Color(0xFFE6EFEC)

val TealPrimaryDark = Color(0xFF8FCFC0)
val TealBackgroundDark = Color(0xFF14201D)
val TealSurfaceDark = Color(0xFF1B2A26)