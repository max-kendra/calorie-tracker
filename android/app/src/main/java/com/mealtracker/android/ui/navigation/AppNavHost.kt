package com.mealtracker.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mealtracker.android.ui.screens.AddItemScreen
import com.mealtracker.android.ui.screens.HomeScreen
import com.mealtracker.android.ui.screens.JournalScreen
import com.mealtracker.android.ui.screens.MacronutrientsScreen
import com.mealtracker.android.ui.screens.MealCalorieGoalScreen
import com.mealtracker.android.ui.screens.MealPlanScreen
import com.mealtracker.android.ui.screens.ProfileScreen

/**
 * The four bottom-nav destinations from the design doc: Home / Journal /
 * Meal Plan / Profile -- deliberately no Coach/Sprout equivalents (those
 * were Foodvisor-specific features, not part of our scope, see design doc).
 */
sealed class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Destination("home", "Home", Icons.Filled.Home)
    object Journal : Destination("journal", "Journal", Icons.Filled.CalendarMonth)
    object MealPlan : Destination("meal_plan", "Meal Plan", Icons.Filled.Restaurant)
    object Profile : Destination("profile", "Profile", Icons.Filled.Person)
}

private val bottomNavDestinations = listOf(
    Destination.Home,
    Destination.Journal,
    Destination.MealPlan,
    Destination.Profile
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavDestinations.forEach { destination ->
                    NavigationBarItem(
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Standard bottom-nav behavior: avoid piling up
                                // a huge back stack as the user bounces between
                                // tabs, and don't create duplicate copies of the
                                // same destination on repeated taps.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Home.route) { HomeScreen() }
            composable(Destination.Journal.route) {
                JournalScreen(onNavigateToAddItem = { navController.navigate("add_item") })
            }
            composable("add_item") {
                AddItemScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() }
                )
            }
            composable(Destination.MealPlan.route) { MealPlanScreen() }
            composable(Destination.Profile.route) {
                ProfileScreen(
                    onNavigateToMacronutrients = { navController.navigate("macronutrients") },
                    onNavigateToMealCalorieGoal = { navController.navigate("meal_calorie_goal") }
                )
            }
            composable("macronutrients") {
                MacronutrientsScreen(
                    onNavigateToProfile = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("meal_calorie_goal") {
                MealCalorieGoalScreen(
                    onNavigateToSetGoal = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
