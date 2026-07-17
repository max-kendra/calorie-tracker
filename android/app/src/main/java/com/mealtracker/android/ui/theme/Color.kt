package com.mealtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

// Teal accent stays app-wide (buttons, rings, etc.) -- but the BACKGROUND
// is back to plain white/near-white (see design discussion: "the
// background shouldn't be blue everywhere"). JournalHeroPastel is used
// ONLY by JournalScreen's own pinned hero section, not set on the
// theme's ColorScheme, so it doesn't leak into any other screen.
val TealPrimary = Color(0xFF2C6E63)
val TealPrimaryContainer = Color(0xFFB6D9D2)
val AppBackground = Color(0xFFFAFAFA)
val TealSurfaceVariant = Color(0xFFF0F0F0)

// Soft pastel, replacing the earlier bold #1FAFED -- see design
// discussion ("that color was too bold... pick out some cute pastel").
val JournalHeroPastel = Color(0xFFBFEAFB)

val TealPrimaryDark = Color(0xFF8FCFC0)
val TealBackgroundDark = Color(0xFF14201D)
val TealSurfaceDark = Color(0xFF1B2A26)