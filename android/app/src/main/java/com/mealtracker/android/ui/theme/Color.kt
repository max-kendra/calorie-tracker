package com.mealtracker.android.ui.theme

import androidx.compose.ui.graphics.Color

// Navy + pastel blue identity (see design discussion: "let's just make
// our theme unified... i like the pastel blue we have for our journal
// hero card... all the dark green buttons would be a navy blue that
// complements the pastel blue"). NavyPrimary drives buttons/the kcal
// ring/anything reading colorScheme.primary in LIGHT mode.
val NavyPrimary = Color(0xFF1B3A5C)
// Dark mode gets its own, brighter primary (see design discussion:
// "the navy text is too dark for dark mode (like the highlighted day
// or when it says how many kg to go)... would something like #365CFF
// work?"). NavyPrimary itself is fine as a BUTTON background in either
// theme (onPrimary=White covers that case regardless), but
// colorScheme.primary also gets read directly as TEXT/icon color in
// several places (calendar day highlights, "X kg to go", etc), where
// navy text on a dark background is illegible -- which is exactly
// what was happening. This is really Material3's own standard
// convention (a theme's primary is usually LIGHTER in dark mode,
// DARKER in light mode, specifically because it doubles as an on-
// background accent color, not just a button fill) -- the earlier
// "same value in both themes" approach fixed the original visibility
// bug but broke this other, equally real use case in the process.
val NavyPrimaryDark = Color(0xFF365CFF)
// Dedicated green for kcal displays specifically, deliberately NOT tied
// to colorScheme.primary (see design discussion: "can the total kcal
// have a dedicated color rather than the system color... i still want
// total kcal to be green in the progress bars", later extended to
// "in meal/item info, the kcal progress bars should also be green").
// Shared/public so it's one source of truth rather than getting
// re-hardcoded per file (HomeScreen's weekly bars, the meal detail
// screen's own kcal progress bar and its compact collapsed-header
// version all read this same constant).
val KcalGreen = Color(0xFF2C6E63)

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

// Dedicated to Journal's big kcal ring specifically (see design
// discussion: "the overall macro circle should be white in dark mode,
// and like a dark gray in light mode" followed by "actually... can we
// just make the overall kcal circle and the value inside it white for
// both themes? i think that'd work better, it's too stark right now").
// Kept as a light/dark PAIR (rather than one shared constant) since the
// ring sits on different backgrounds in each theme and a future design
// change might want them to diverge again -- right now they just
// happen to be the same value.
val KcalRingLight = Color.White
val KcalRingDark = Color.White

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