package com.mealtracker.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mealtracker.android.ui.screens.CalorieGoalScreen
import com.mealtracker.android.ui.screens.EditProfileScreen
import com.mealtracker.android.ui.screens.HomeScreen
import com.mealtracker.android.ui.screens.JournalScreen
import com.mealtracker.android.ui.screens.MacronutrientsScreen
import com.mealtracker.android.ui.screens.MealCalorieGoalScreen
import com.mealtracker.android.ui.screens.MealDetailScreen
import com.mealtracker.android.ui.screens.MealPlanScreen
import com.mealtracker.android.ui.screens.OnboardingGateViewModel
import com.mealtracker.android.ui.screens.OnboardingScreen
import com.mealtracker.android.ui.screens.ProfileScreen
import com.mealtracker.android.ui.screens.SettingsScreen
import com.mealtracker.android.ui.screens.WeightGoalScreen

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

// Routes that should NOT show the bottom nav bar -- the required
// onboarding flow (and the brief gate check before it) is deliberately
// full-screen, no tab-bar escape hatch, since the rest of the app
// assumes a complete profile + active goal exist.
private val routesWithoutBottomBar = setOf("gate", "onboarding", "meal_detail/{date}/{mealType}")

// Routes whose own hero background is meant to bleed up behind the
// status bar (Journal's pastel kcal-ring section, Meal Detail's compact
// header when its sheet is expanded) -- these get NO automatic
// statusBarsPadding from AppNavHost, and are responsible for padding
// their own readable content below the status bar themselves (see
// JournalScreen's top spacer, MealDetailScreen's CompactMealHeader).
// Every other route gets padded below the status bar automatically,
// same as before.
private val edgeToEdgeStatusBarRoutes = setOf("journal", "meal_detail/{date}/{mealType}")

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        // Everywhere EXCEPT edgeToEdgeStatusBarRoutes still gets a
        // status-bar-safe top inset -- just applied explicitly below via
        // statusBarsPadding() instead of Scaffold's default, so the
        // routes that want their own hero to bleed up behind the status
        // bar (see that set's doc comment) can opt out of it.
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.statusBars),
        bottomBar = {
            if (currentRoute !in routesWithoutBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "gate",
            modifier = Modifier
                .padding(innerPadding)
                .let { base -> if (currentRoute in edgeToEdgeStatusBarRoutes) base else base.statusBarsPadding() }
        ) {
            composable("gate") {
                val gateViewModel: OnboardingGateViewModel = viewModel()
                val gateState by gateViewModel.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    gateViewModel.checkSetupComplete()
                }

                LaunchedEffect(gateState.needsOnboarding) {
                    val needsOnboarding = gateState.needsOnboarding ?: return@LaunchedEffect
                    val destination = if (needsOnboarding) "onboarding" else Destination.Home.route
                    navController.navigate(destination) {
                        popUpTo("gate") { inclusive = true }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            composable("onboarding") {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Destination.Home.route) {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }
            composable(Destination.Home.route) { HomeScreen() }
            composable(Destination.Journal.route) {
                JournalScreen(
                    onNavigateToMealDetail = { mealType ->
                        navController.navigate("meal_detail/${java.time.LocalDate.now()}/$mealType")
                    }
                )
            }
            composable("meal_detail/{date}/{mealType}") { backStackEntry ->
                val dateArg = backStackEntry.arguments?.getString("date") ?: java.time.LocalDate.now().toString()
                val mealTypeArg = backStackEntry.arguments?.getString("mealType") ?: "breakfast"
                MealDetailScreen(
                    date = java.time.LocalDate.parse(dateArg),
                    mealType = mealTypeArg,
                    onBack = { navController.popBackStack() }
                )
            }
            // "add_item" as its own standalone route is gone -- that
            // flow (AddItemScreen) is now embedded directly inside
            // MealDetailScreen's Add Item sheet instead of being
            // navigated to (see design discussion: "we want it inside
            // the card"). It was only ever reachable from there, so
            // there's nothing else that needs this route.
            composable(Destination.MealPlan.route) { MealPlanScreen() }
            composable(Destination.Profile.route) {
                ProfileScreen(
                    onNavigateToSettings = { navController.navigate("profile_settings") }
                )
            }
            composable("profile_settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToEditProfile = { navController.navigate("profile_settings/edit") },
                    onNavigateToCalorieGoal = { navController.navigate("profile_settings/calorie_goal") },
                    onNavigateToMealCalorieGoal = { navController.navigate("meal_calorie_goal") },
                    onNavigateToMacronutrients = { navController.navigate("macronutrients") },
                    onNavigateToWeightGoal = { navController.navigate("profile_settings/weight_goal") },
                    onNavigateToHealthConnect = { navController.navigate("profile_settings/health_connect") }
                )
            }
            composable("profile_settings/health_connect") {
                HealthConnectSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("profile_settings/edit") {
                EditProfileScreen(onBack = { navController.popBackStack() })
            }
            composable("profile_settings/calorie_goal") {
                CalorieGoalScreen(onBack = { navController.popBackStack() })
            }
            composable("profile_settings/weight_goal") {
                WeightGoalScreen(onBack = { navController.popBackStack() })
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