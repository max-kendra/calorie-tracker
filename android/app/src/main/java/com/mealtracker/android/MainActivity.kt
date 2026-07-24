package com.mealtracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.ui.navigation.AppNavHost
import com.mealtracker.android.ui.theme.MealTrackerTheme
import com.mealtracker.android.ui.theme.ThemePreference
import com.mealtracker.android.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Opportunistic: pushes today's meals to Health Connect on cold
        // start so data logged before nutrition export was revisited on
        // its own meal screen (see MealDetailScreen's LaunchedEffect)
        // still shows up without waiting for that. syncToday() no-ops
        // internally if export isn't enabled/permitted, and swallows
        // its own errors here since this is opportunistic - the
        // Settings "sync all past meals" button is the real fallback.
        lifecycleScope.launch {
            try {
                HealthConnectManager.syncToday(this@MainActivity)
            } catch (e: Exception) {
                // Ignore - opportunistic sync, manual sync in Settings covers recovery.
            }
        }

        setContent {
            // Loaded once per process start -- SettingsScreen's picker
            // updates this same state (see onThemePreferenceChange
            // below), so a change there recomposes MealTrackerTheme
            // immediately without needing to restart the activity.
            var themePreference by remember { mutableStateOf(ThemePreferenceStore.get(this)) }
            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            MealTrackerTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavHost(
                        themePreference = themePreference,
                        onThemePreferenceChange = { newPreference ->
                            themePreference = newPreference
                            ThemePreferenceStore.set(this, newPreference)
                        }
                    )
                }
            }
        }
    }
}