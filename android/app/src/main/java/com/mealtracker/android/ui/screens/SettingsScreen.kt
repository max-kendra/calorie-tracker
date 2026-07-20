package com.mealtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mealtracker.android.ui.theme.ThemePreference

/**
 * Reached from the settings-gear icon on the main Profile screen. Every
 * goal-related setting each gets its own screen (see design discussion) --
 * this is purely a navigation hub, no editable state of its own.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToCalorieGoal: () -> Unit,
    onNavigateToMealCalorieGoal: () -> Unit,
    onNavigateToMacronutrients: () -> Unit,
    onNavigateToWeightGoal: () -> Unit,
    onNavigateToHealthConnect: () -> Unit,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        SettingsSectionHeader("Appearance")
        ThemePicker(selected = themePreference, onSelect = onThemePreferenceChange)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        SettingsSectionHeader("Profile")
        SettingsRow("My profile", "Name, height, hormone, age", onNavigateToEditProfile)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        SettingsSectionHeader("Goals")
        SettingsRow("Calorie goal", "TDEE calculation and daily target", onNavigateToCalorieGoal)
        SettingsRow("Calorie goals by meal", "Change calories for each meal", onNavigateToMealCalorieGoal)
        SettingsRow("Fat, carbs, fiber and protein goals", "Edit your macronutrient goals", onNavigateToMacronutrients)
        SettingsRow("Weight goal", "Set your starting and goal weight", onNavigateToWeightGoal)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        SettingsSectionHeader("Health Connect")
        SettingsRow("Health Connect", "Sync weight and nutrition with Health Connect", onNavigateToHealthConnect)
    }
}

/** Hand-rolled 3-way segmented control rather than Material3's
 * SegmentedButton -- keeps this consistent with the rest of the app's
 * approach of building small UI pieces directly (see CropDialog's doc
 * comment on library churn) instead of reaching for a less-certain
 * newer M3 component for a single simple picker. */
@Composable
private fun ThemePicker(selected: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ThemePreference.entries.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    HorizontalDivider()
}