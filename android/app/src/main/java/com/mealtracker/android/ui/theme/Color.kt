package com.mealtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

// Teal accent stays app-wide (buttons, rings, etc.) - but the BACKGROUND
// is back to plain white/near-white (see design discussion: "the
// background shouldn't be blue everywhere"). JournalHeroPastel is used
// ONLY by JournalScreen's own pinned hero section, not set on the
// theme's ColorScheme, so it doesn't leak into any other screen.
val TealPrimary = Color(0xFF2C6E63)
val TealPrimaryContainer = Color(0xFFB6D9D2)
val AppBackground = Color(0xFFFAFAFA)
val TealSurfaceVariant = Color(0xFFF0F0F0)

// Soft pastel, replacing the earlier bold #1FAFED - see design
// discussion ("that color was too bold... pick out some cute pastel").
val JournalHeroPastel = Color(0xFFBFEAFB)
// Darker, desaturated version of the same blue hue for dark mode - see
// design discussion ("the pastel hero card colors do have to be a
// little different [in dark mode]... darker versions of themselves").
val JournalHeroPastelDark = Color(0xFF1B4A5A)

// Dark theme - per design discussion, this is meant to read as "mostly
// an inversion" (dark gray backgrounds, white text/neutral icons) while
// every COLORED/branded element (teal accent, JournalHeroPastel,
// MacroColors, MealVisuals meal-icon tints, etc.) stays exactly the same
// as light mode rather than getting its own darker variant. That's why
// Theme.kt's DarkColorScheme uses TealPrimary directly again, not a
// separate muted color the way TealPrimaryDark used to - deliberate
// now, not an oversight.
val AppBackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantDark = Color(0xFF2C2C2C)
val OnSurfaceDark = Color(0xFFF2F2F2)
val OnSurfaceVariantDark = Color(0xFFB0B0B0)