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
import com.mealtracker.android.ui.navigation.AppNavHost
import com.mealtracker.android.ui.theme.MealTrackerTheme
import com.mealtracker.android.ui.theme.ThemePreference
import com.mealtracker.android.ui.theme.ThemePreferenceStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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