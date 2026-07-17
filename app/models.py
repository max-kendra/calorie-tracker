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

/**
 * Mirrors LogOut from app/schemas.py. IMPORTANT: kcal_logged/protein_g_logged/
 * etc are already rounded integers on the backend (see the rounding policy
 * in the backend's app/nutrition.py) -- these are the FROZEN snapshot
 * values from when the item was logged, not live-recomputed. Exactly one
 * of itemId/recipeId will be non-null (matches the backend's CHECK
 * constraint), with itemName/recipeName correspondingly set for display.
 */
@Serializable
data class Log(
    val id: Int,
    val date: String, // ISO date string, e.g. "2026-07-13"
    @SerialName("meal_type") val mealType: String,
    @SerialName("item_id") val itemId: Int? = null,
    @SerialName("recipe_id") val recipeId: Int? = null,
    @SerialName("serving_size_id") val servingSizeId: Int? = null,
    val quantity: String,
    @SerialName("logged_at") val loggedAt: String,
    @SerialName("kcal_logged") val kcalLogged: Int,
    @SerialName("protein_g_logged") val proteinGLogged: Int,
    @SerialName("carbs_g_logged") val carbsGLogged: Int,
    @SerialName("fat_g_logged") val fatGLogged: Int,
    @SerialName("fiber_g_logged") val fiberGLogged: Int,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("recipe_name") val recipeName: String? = null
)

/**
 * Mirrors NutritionTotals from app/schemas.py -- always whole numbers,
 * rounded UP for display on the backend already (see the backend's
 * rounding policy). Never re-round these client-side.
 */
@Serializable
data class NutritionTotals(
    val kcal: Int,
    @SerialName("protein_g") val proteinG: Int,
    @SerialName("carbs_g") val carbsG: Int,
    @SerialName("fat_g") val fatG: Int,
    @SerialName("fiber_g") val fiberG: Int
)

/**
 * Mirrors MealGoalSplitOut from app/schemas.py. pctOfKcal is the stored
 * percentage (e.g. "25.0" for 25%); computedTotals is derived server-side
 * from the parent goal's targets x this percentage, already rounded.
 */
@Serializable
data class MealGoalSplit(
    @SerialName("meal_type") val mealType: String,
    @SerialName("pct_of_kcal") val pctOfKcal: String,
    @SerialName("computed_totals") val computedTotals: NutritionTotals
)

/**
 * Mirrors GoalOut from app/schemas.py. Fetch via GET /goals/active to get
 * whatever goal is currently in effect (end_date IS NULL on the backend).
 */
@Serializable
data class Goal(
    val id: Int,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("kcal_target") val kcalTarget: String,
    @SerialName("protein_g_target") val proteinGTarget: String,
    @SerialName("carbs_g_target") val carbsGTarget: String,
    @SerialName("fat_g_target") val fatGTarget: String,
    @SerialName("fiber_g_target") val fiberGTarget: String,
    @SerialName("meal_splits") val mealSplits: List<MealGoalSplit> = emptyList()
)

/**
 * Request body for POST /goals. Unlike the response models above, this
 * sends plain JSON numbers (Double), not Decimal-as-string -- FastAPI's
 * Pydantic Decimal fields accept a numeric JSON value fine on the way
 * in; the string-encoding behavior is only how Decimal comes back OUT
 * in a response, to preserve precision for display.
 */
@Serializable
data class GoalCreateRequest(
    @SerialName("start_date") val startDate: String,
    @SerialName("kcal_target") val kcalTarget: Double,
    @SerialName("protein_g_target") val proteinGTarget: Double,
    @SerialName("carbs_g_target") val carbsGTarget: Double,
    @SerialName("fat_g_target") val fatGTarget: Double,
    @SerialName("fiber_g_target") val fiberGTarget: Double
    // meal_splits omitted -- backend defaults to an even 25/25/25/25
    // split when not provided (see backend app/routers/goals.py).
)

/**
 * Request body for PATCH /goals/{id}. All fields optional, matching the
 * backend's GoalUpdate schema -- send only what actually changed.
 */
@Serializable
data class GoalUpdateRequest(
    @SerialName("kcal_target") val kcalTarget: Double? = null,
    @SerialName("protein_g_target") val proteinGTarget: Double? = null,
    @SerialName("carbs_g_target") val carbsGTarget: Double? = null,
    @SerialName("fat_g_target") val fatGTarget: Double? = null,
    @SerialName("fiber_g_target") val fiberGTarget: Double? = null
)

/**
 * One entry in the bulk meal-splits replace request (PUT
 * /goals/{id}/meal-splits). pctOfKcal sent as a plain number, same
 * "requests send numbers, responses send Decimal-as-string" rule as
 * GoalCreateRequest/GoalUpdateRequest above.
 */
@Serializable
data class MealGoalSplitRequest(
    @SerialName("meal_type") val mealType: String,
    @SerialName("pct_of_kcal") val pctOfKcal: Double
)

@Serializable
data class MealGoalSplitsUpdateRequest(
    val splits: List<MealGoalSplitRequest>
)

/**
 * Request body for POST /recipes -- used for the "star icon: save this
 * meal" feature. A saved Meal is just a Recipe with recipeType="meal"
 * and servings=1 (see backend design doc).
 */
@Serializable
data class RecipeIngredientCreateRequest(
    @SerialName("item_id") val itemId: Int,
    @SerialName("quantity_g") val quantityG: Double
)

@Serializable
data class RecipeCreateRequest(
    val name: String,
    @SerialName("recipe_type") val recipeType: String = "meal",
    val servings: Double = 1.0,
    val ingredients: List<RecipeIngredientCreateRequest> = emptyList()
)

/** Minimal response model -- we only need to confirm success, extra
 * fields in the real response are ignored (ignoreUnknownKeys=true). */
@Serializable
data class Recipe(
    @SerialName("recipe_id") val recipeId: Int,
    val name: String
)

/**
 * Mirrors UserProfileOut from app/schemas.py. All fields nullable/optional
 * since a fresh profile starts empty (auto-created on first GET /profile).
 */
@Serializable
data class UserProfile(
    val id: Int,
    val name: String? = null,
    @SerialName("profile_pic_path") val profilePicPath: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    val age: Int? = null,
    @SerialName("weight_kg") val weightKg: String? = null,
    @SerialName("starting_weight_kg") val startingWeightKg: String? = null,
    @SerialName("goal_weight_kg") val goalWeightKg: String? = null,
    @SerialName("primary_hormone") val primaryHormone: String? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
    @SerialName("goal_type") val goalType: String? = null,
    val timezone: String,
    @SerialName("updated_at") val updatedAt: String
)

/**
 * Request body for PATCH /profile. Sends plain numbers (not
 * Decimal-as-string), same rule as the other *Request models above.
 */
@Serializable
data class UserProfileUpdateRequest(
    val name: String? = null,
    @SerialName("height_cm") val heightCm: Int? = null,
    val age: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("starting_weight_kg") val startingWeightKg: Double? = null,
    @SerialName("goal_weight_kg") val goalWeightKg: Double? = null,
    @SerialName("primary_hormone") val primaryHormone: String? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
    @SerialName("goal_type") val goalType: String? = null
)

/**
 * Mirrors KcalGoalCalculationResult from app/schemas.py. All values are
 * whole integers now (rounded to the nearest 25 for
 * recommended/low/high on the backend) -- no fractional-calorie parsing
 * needed here.
 */
@Serializable
data class KcalGoalCalculationResult(
    val bmr: Int,
    val tdee: Int,
    @SerialName("recommended_kcal") val recommendedKcal: Int,
    @SerialName("kcal_low") val kcalLow: Int,
    @SerialName("kcal_high") val kcalHigh: Int,
    @SerialName("floor_applied") val floorApplied: Boolean
)

/**
 * Mirrors BarcodeScanResult from app/schemas.py.
 *
 * IMPORTANT (per real-world testing, see backend README): a decoded
 * barcode is NOT guaranteed correct even if `checksumValid` is true --
 * the client MUST show `barcode` to the user for visual confirmation
 * against the physical package before using it, never auto-proceed.
 */
@Serializable
data class BarcodeScanResult(
    val barcode: String? = null,
    @SerialName("decoder_used") val decoderUsed: String? = null,
    @SerialName("checksum_valid") val checksumValid: Boolean? = null,
    val item: Item? = null
)

/**
 * Mirrors OcrMacros from app/schemas.py -- fields absent (not zero) when
 * OCR didn't confidently extract them.
 */
@Serializable
data class OcrMacros(
    @SerialName("kcal_100g") val kcal100g: String? = null,
    @SerialName("protein_100g") val protein100g: String? = null,
    @SerialName("carbs_100g") val carbs100g: String? = null,
    @SerialName("fat_100g") val fat100g: String? = null,
    @SerialName("fiber_100g") val fiber100g: String? = null,
    @SerialName("sugar_100g") val sugar100g: String? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: String? = null,
    @SerialName("sodium_mg_100g") val sodiumMg100g: String? = null
)

/**
 * Mirrors ProductPhotoScanResult from app/schemas.py. guessedName/
 * guessedBrand are rough heuristics (NOT a confident extraction the way
 * OcrMacros is for nutrition labels) -- always show these as pre-filled
 * but clearly editable text, never as if they're confirmed correct. See
 * the backend schema's docstring for why this heuristic is inherently
 * weaker than the label OCR.
 */
@Serializable
data class ProductPhotoScanResult(
    @SerialName("image_path") val imagePath: String,
    @SerialName("raw_text") val rawText: String,
    @SerialName("guessed_name") val guessedName: String? = null,
    @SerialName("guessed_brand") val guessedBrand: String? = null
)

/**
 * Mirrors OcrScanResult from app/schemas.py. per100gConfirmed being false
 * means the label's values might be per-serving, not per-100g -- the
 * client should surface that as a visible warning (see backend README).
 */
@Serializable
data class OcrScanResult(
    @SerialName("raw_text") val rawText: String,
    @SerialName("detected_language") val detectedLanguage: String? = null,
    @SerialName("per_100g_confirmed") val per100gConfirmed: Boolean = false,
    val macros: OcrMacros
)

/**
 * Request body for POST /items. Sends plain numbers (not
 * Decimal-as-string), same rule as the other *Request models.
 */
@Serializable
data class ItemCreateRequest(
    val name: String,
    val barcode: String? = null,
    val brand: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("kcal_100g") val kcal100g: Double? = null,
    @SerialName("protein_100g") val protein100g: Double? = null,
    @SerialName("carbs_100g") val carbs100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("sugar_100g") val sugar100g: Double? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: Double? = null,
    @SerialName("sodium_mg_100g") val sodiumMg100g: Double? = null,
    val type: String = "product",
    val origin: String = "manual"
)

/**
 * Request body for POST /logs. Mirrors LoggableEntryBase (backend) --
 * exactly one of itemId/recipeId must be set. quantity semantics (see
 * that schema's docstring): with no servingSizeId, quantity is grams
 * directly. Used by MealDetailViewModel's "quick log" flow (Saved/
 * Search/Barcode-match in the Add Item sheet), which currently always
 * sends a flat 100g default rather than prompting for quantity -- see
 * that ViewModel for why.
 */
@Serializable
data class LogCreateRequest(
    val date: String,
    @SerialName("meal_type") val mealType: String,
    @SerialName("item_id") val itemId: Int? = null,
    @SerialName("recipe_id") val recipeId: Int? = null,
    @SerialName("serving_size_id") val servingSizeId: Int? = null,
    val quantity: Double
)

/** Quantity-only edit of an existing log -- see backend LogUpdate's doc
 * comment (item/recipe/date/meal_type aren't editable this way). */
@Serializable
data class LogUpdateRequest(
    val quantity: Double? = null,
    @SerialName("serving_size_id") val servingSizeId: Int? = null
)