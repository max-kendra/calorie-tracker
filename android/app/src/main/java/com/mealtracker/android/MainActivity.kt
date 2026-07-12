package com.mealtracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mealtracker.android.ui.navigation.AppNavHost
import com.mealtracker.android.ui.theme.MealTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MealTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
