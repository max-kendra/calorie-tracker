package com.mealtracker.android.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String
)

/**
 * Mirrors ServingSizeOut from app/schemas.py.
 */
@Serializable
data class ServingSize(
    val id: Int,
    @SerialName("item_id") val itemId: Int,
    val name: String,
    @SerialName("weight_g") val weightG: String // Decimal comes over the wire as a string
)

/**
 * Mirrors ItemOut from app/schemas.py. Nutrition fields are nullable
 * Strings (not Double) because:
 *   1. FastAPI serializes Decimal as a JSON string by default, not a
 *      float -- keeps precision exact, no floating-point surprises.
 *   2. Fields are genuinely optional (an item might not have every
 *      macro filled in).
 * Parse to a real number only where you need to do math with it.
 */
@Serializable
data class Item(
    @SerialName("item_id") val itemId: Int,
    val name: String,
    val barcode: String? = null,
    val brand: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("kcal_100g") val kcal100g: String? = null,
    @SerialName("protein_100g") val protein100g: String? = null,
    @SerialName("carbs_100g") val carbs100g: String? = null,
    @SerialName("fat_100g") val fat100g: String? = null,
    @SerialName("fiber_100g") val fiber100g: String? = null,
    @SerialName("sugar_100g") val sugar100g: String? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: String? = null,
    @SerialName("sodium_mg_100g") val sodiumMg100g: String? = null,
    val type: String,
    val origin: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("serving_sizes") val servingSizes: List<ServingSize> = emptyList()
)
