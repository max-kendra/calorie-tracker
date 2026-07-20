package com.mealtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

// Navy + pastel blue identity (see design discussion: "let's just make
// our theme unified... i like the pastel blue we have for our journal
// hero card... all the dark green buttons would be a navy blue that
// complements the pastel blue"). NavyPrimary drives buttons/the kcal
// ring/anything reading colorScheme.primary -- ONE navy value in both
// light and dark mode, deliberately (that's the actual fix: the old
// TealPrimary was ALSO the same dark green in both themes, which is
// exactly why it read fine in light mode but nearly vanished against
// dark backgrounds in dark mode -- a dark, saturated color needs a
// genuinely dark-mode-appropriate companion, not just itself again).
// JournalHeroPastel/Dark (below) fill the softer "accent" role as
// primaryContainer, same blue hue family as the navy, so the whole
// palette reads as one coordinated identity instead of separate
// systems bumping into each other.
val NavyPrimary = Color(0xFF1B3A5C)
val AppBackground = Color(0xFFFAFAFA)
val NeutralSurfaceVariant = Color(0xFFF0F0F0)

// Soft pastel, replacing the earlier bold #1FAFED - see design
// discussion ("that color was too bold... pick out some cute pastel").
// Now doing double duty as the theme's primaryContainer too (see
// Theme.kt), not just Journal's own hero section.
val JournalHeroPastel = Color(0xFFBFEAFB)
// Darker, desaturated version of the same blue hue for dark mode - see
// design discussion ("the pastel hero card colors do have to be a
// little different [in dark mode]... darker versions of themselves").
// Same double duty as primaryContainer in dark mode.
val JournalHeroPastelDark = Color(0xFF1B4A5A)

// Dark theme - mostly an inversion (dark gray backgrounds, white text/
// neutral icons); every COLORED/branded element (MacroColors,
// MealVisuals meal-icon tints, etc.) stays exactly the same as light
// mode rather than getting its own darker variant, EXCEPT the
// primary/primaryContainer pair, which now deliberately DO get their
// own dark-mode-appropriate values (NavyPrimary is shared, but
// JournalHeroPastelDark stands in for JournalHeroPastel) - see this
// file's own top comment for why that one pair needed it and nothing
// else does.
val AppBackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantDark = Color(0xFF2C2C2C)
val OnSurfaceDark = Color(0xFFF2F2F2)
val OnSurfaceVariantDark = Color(0xFFB0B0B0)