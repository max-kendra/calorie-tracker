package com.mealtracker.android.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Default icon+background for an Item (type: "product"/"ingredient") or
 * Recipe (recipeType: "recipe"/"meal") that has no image_path -- used
 * everywhere a thumbnail shows (search results, logged-items rows, the
 * item/recipe detail hero, the matched-barcode toast) so a missing
 * photo isn't just a blank gray box. Same pattern/reasoning as
 * MealVisuals (breakfast/lunch/dinner/snack icons), but keyed off the
 * catalog type instead of meal type -- these are conceptually different
 * groupings (a "snack" meal-slot can contain a "product" item), so
 * they're deliberately two separate objects rather than one shared
 * `when`.
 */
object CatalogVisuals {
    @Composable
    fun iconTint(): Color = if (isSystemInDarkTheme()) Color(0xFFD7CCC8) else Color(0xFF5D4037)

    fun iconFor(type: String): ImageVector = when (type) {
        "ingredient" -> Icons.Filled.Egg
        "recipe" -> Icons.Filled.MenuBook
        "meal" -> Icons.Filled.RestaurantMenu
        else -> Icons.Filled.ShoppingBag // "product" and anything unrecognized
    }

    @Composable
    fun backgroundFor(type: String): Color {
        val dark = isSystemInDarkTheme()
        return when (type) {
            "ingredient" -> if (dark) Color(0xFF28402A) else Color(0xFFC8E6C9)
            "recipe" -> if (dark) Color(0xFF352D4A) else Color(0xFFD1C4E9)
            "meal" -> if (dark) Color(0xFF4A3418) else Color(0xFFFFE0B2)
            else -> if (dark) Color(0xFF2C2C2C) else Color(0xFFECEFF1) // "product"
        }
    }
}