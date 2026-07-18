package com.mealtracker.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mealtracker.android.ui.components.PageIndicator

private const val STEP_COUNT = 5

/**
 * First-run required setup -- reached instead of the normal Home/Journal
 * tabs when the profile is incomplete or no calorie goal exists yet (see
 * OnboardingGateViewModel, which decides this at app launch). Reuses the
 * exact same four screens normally reached individually via Settings --
 * no separate onboarding-specific UI was built, just a fixed order +
 * auto-advance wiring (onSaved) + a page-indicator strip pinned under
 * them.
 *
 * Order: profile basics -> weight goal -> calorie goal (weight/activity/
 * TDEE calc/save) -> macro distribution -> Health Connect. This matches
 * the dependency order of the data itself -- e.g. the calorie-goal
 * calculation needs height+age from step 1, and the macro screen needs
 * an active goal to exist, which step 3 is what creates. Health Connect
 * comes last since it depends on nothing else and nothing else depends
 * on it.
 *
 * Deliberately no skip option and no way to back out past step 0 for
 * steps 0-3 -- see design discussion: every new item / the rest of the
 * app assumes a complete profile + active goal exist, so those are
 * required, not optional, the first time through. Step 4 (Health
 * Connect) is the one exception -- see HealthConnectOnboardingStep's
 * own doc comment for why that one IS skippable.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (step) {
                0 -> EditProfileScreen(
                    onBack = { /* no-op: nothing to go back to before step 0 */ },
                    onSaved = { step = 1 }
                )
                1 -> WeightGoalScreen(
                    onBack = { step = 0 },
                    onSaved = { step = 2 }
                )
                2 -> CalorieGoalScreen(
                    onBack = { step = 1 },
                    onSaved = { step = 3 }
                )
                3 -> MacronutrientsScreen(
                    onBack = { step = 2 },
                    onSaved = { step = 4 }
                )
                4 -> HealthConnectOnboardingStep(onDone = onComplete)
            }
        }
        PageIndicator(pageCount = STEP_COUNT, currentPage = step)
    }
}