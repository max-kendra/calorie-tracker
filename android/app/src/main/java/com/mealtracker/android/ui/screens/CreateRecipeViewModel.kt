package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeCreateRequest
import com.mealtracker.android.network.models.RecipeIngredientCreateRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val INGREDIENT_SEARCH_DEBOUNCE_MS = 350L

/** One ingredient added to the recipe being built.
 *
 * Stores the SAME quantity/servingSizeId pair the backend and every
 * other picker in the app use (quantity is a multiplier of the serving
 * if servingSizeId is set, otherwise raw grams) - NOT pre-collapsed to
 * grams. Collapsing to grams here was the reason editing an already-
 * added ingredient was effectively impossible: reopening the picker had
 * no way to recover "2 slices", only "75g", so any edit silently
 * dropped back to raw grams even if that's not how it was entered (see
 * design discussion: "can't edit amounts once added, gotta delete and
 * re-add it instead"). */
data class CreateRecipeIngredientRow(
    val item: Item,
    val quantity: Double,
    val servingSizeId: Int?
) {
    val servingSize get() = item.servingSizes.find { it.id == servingSizeId }
    val grams: Double
        get() = if (servingSize != null) quantity * (servingSize!!.weightG.toDoubleOrNull() ?: 0.0) else quantity
    val kcal: Double get() = (item.kcal100g?.toDoubleOrNull() ?: 0.0) * grams / 100.0
    val proteinG: Double get() = (item.protein100g?.toDoubleOrNull() ?: 0.0) * grams / 100.0
    val carbsG: Double get() = (item.carbs100g?.toDoubleOrNull() ?: 0.0) * grams / 100.0
    val fatG: Double get() = (item.fat100g?.toDoubleOrNull() ?: 0.0) * grams / 100.0
    val fiberG: Double get() = (item.fiber100g?.toDoubleOrNull() ?: 0.0) * grams / 100.0
}

/** Live running totals for the recipe being built, computed straight
 * from `ingredients` client-side - same per-100g x grams/100 math the
 * backend's compute_recipe_totals does, just done here too so the
 * screen can show "X Cal total / Y Cal per serving" WHILE building,
 * instead of only after saving (see design discussion: "you have to
 * create the recipe first to see the total and per-serving calories
 * and macros"). Never sent anywhere - purely a preview; the backend
 * remains the source of truth once saved. */
data class RecipeTotalsPreview(
    val kcal: Double, val proteinG: Double, val carbsG: Double, val fatG: Double, val fiberG: Double
) {
    fun perServing(servings: Double) = if (servings > 0) {
        RecipeTotalsPreview(kcal / servings, proteinG / servings, carbsG / servings, fatG / servings, fiberG / servings)
    } else this
}

private fun List<CreateRecipeIngredientRow>.totals() = RecipeTotalsPreview(
    kcal = sumOf { it.kcal },
    proteinG = sumOf { it.proteinG },
    carbsG = sumOf { it.carbsG },
    fatG = sumOf { it.fatG },
    fiberG = sumOf { it.fiberG }
)

/**
 * Split the same way AddItemScreen's phases are -- a linear sequence,
 * not a nav stack, since backing out mid-build should drop the whole
 * in-progress recipe (see design discussion for why the details/
 * ingredients split happened at all: "name the recipe and give the
 * amount of servings on a separate screen, then on the other screen we
 * solely focus on adding ingredients" -- letting that second screen
 * reuse the main search's Search/Barcode toggle without a redundant
 * third "Create" button, since we're already inside Create).
 */
enum class CreateRecipePhase { DETAILS, INGREDIENTS }

/** Mirrors AddItemSheetMode, scoped down to just the two methods that
 * make sense once you're already inside Create -- no third option here
 * since Create itself is what got you to this screen. */
enum class CreateRecipeIngredientMode { SEARCH, BARCODE }

data class CreateRecipeUiState(
    val phase: CreateRecipePhase = CreateRecipePhase.DETAILS,
    val ingredientMode: CreateRecipeIngredientMode = CreateRecipeIngredientMode.SEARCH,
    val name: String = "",
    val servings: String = "1",
    val ingredients: List<CreateRecipeIngredientRow> = emptyList(),
    val ingredientSearchQuery: String = "",
    val ingredientSearchResults: List<Item> = emptyList(),
    val isSearchingIngredients: Boolean = false,
    // Non-null while the quantity-picker dialog (ItemQuantityDialog) is
    // open for this item -- mirrors MealDetailViewModel's equivalent
    // quantity-picker state exactly, just scoped to this ViewModel
    // instead since a recipe's ingredient list isn't part of
    // MealDetailViewModel's own state.
    val itemForQuantityPicker: Item? = null,
    val quantityPickerInput: String = "100",
    val quantityPickerServingSizeId: Int? = null,
    val showCreateServingDialog: Boolean = false,
    val newServingName: String = "",
    val newServingWeightG: String = "",
    val isCreatingServing: Boolean = false,
    val createServingError: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    // Non-null once the recipe has been created -- the screen shows a
    // confirmation with "log to this meal" / "done" instead of the
    // build form once this is set, rather than a separate phase enum
    // (the created recipe itself IS the signal that we're done building).
    val createdRecipe: Recipe? = null
) {
    val isDetailsValid: Boolean
        get() = name.isNotBlank() && (servings.toDoubleOrNull() ?: 0.0) > 0.0

    val isSaveValid: Boolean
        get() = isDetailsValid && ingredients.isNotEmpty()

    /** Whole-recipe totals from what's added so far, and the same
     * divided by the servings count - see RecipeTotalsPreview's own
     * doc comment for why this is computed here rather than waiting on
     * a save round-trip. */
    val totalsPreview: RecipeTotalsPreview get() = ingredients.totals()
    val perServingPreview: RecipeTotalsPreview
        get() = totalsPreview.perServing(servings.toDoubleOrNull() ?: 0.0)
}

/**
 * Backs the "Create" method on the meal-detail add sheet (see design
 * discussion: a third button alongside Search/Barcode, for building a
 * brand-new recipe rather than logging an existing item). Scoped per
 * (date, mealType) via viewModel(key = ...) at the call site -- see
 * MealDetailScreen's CREATE branch -- so switching meals or re-entering
 * after a save starts a fresh build rather than resuming a
 * half-finished one from elsewhere.
 *
 * The backend already fully supported recipe creation (POST /recipes
 * with an embedded ingredients list) before this screen existed -- this
 * is purely a client-side gap being filled, not new backend work.
 *
 * Ingredient search/quantity-picking deliberately mirrors
 * MealDetailViewModel's own search + ItemLogPageDialog pattern as
 * closely as this ViewModel's different context allows (no meal/day to
 * compare a goal against, see ItemQuantityDialog's own doc comment) --
 * per design discussion, reusing that pattern (image, brand,
 * quick-add preview, tap-to-open-quantity-picker) rather than a
 * bespoke, simpler ingredient list was the explicit ask.
 */
class CreateRecipeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CreateRecipeUiState())
    val uiState: StateFlow<CreateRecipeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    // --- Details phase ---

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateServings(servings: String) {
        _uiState.value = _uiState.value.copy(servings = servings)
    }

    fun proceedToIngredients() {
        if (!_uiState.value.isDetailsValid) return
        _uiState.value = _uiState.value.copy(phase = CreateRecipePhase.INGREDIENTS)
    }

    fun backToDetails() {
        _uiState.value = _uiState.value.copy(phase = CreateRecipePhase.DETAILS)
    }

    // --- Ingredients phase: method toggle ---

    fun selectIngredientMode(mode: CreateRecipeIngredientMode) {
        _uiState.value = _uiState.value.copy(ingredientMode = mode)
    }

    // --- Ingredients phase: search ---

    fun updateIngredientSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(ingredientSearchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(ingredientSearchResults = emptyList(), isSearchingIngredients = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(INGREDIENT_SEARCH_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isSearchingIngredients = true)
            try {
                val results = ApiClient.service.searchItems(query = query)
                _uiState.value = _uiState.value.copy(isSearchingIngredients = false, ingredientSearchResults = results)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearchingIngredients = false, ingredientSearchResults = emptyList())
            }
        }
    }

    // --- Ingredients phase: quantity picker (opened by tapping a search result) ---

    /** Priority order: this ingredient's existing quantity IN THIS
     * recipe (if already added), then this session's lastLoggedAmounts
     * map, then the item's own persisted last-logged fields (survives
     * across meals/days/sessions - see design discussion: "if i logged
     * 12g of something for lunch and then go to log dinner, it's 100g
     * again"), then finally a flat 100g default. */
    fun openQuantityPicker(item: Item, lastLoggedAmounts: Map<Int, LoggedAmount> = emptyMap()) {
        val existing = _uiState.value.ingredients.find { it.item.itemId == item.itemId }
        val remembered = lastLoggedAmounts[item.itemId]
        val quantity: Double?
        val servingSizeId: Int?
        when {
            existing != null -> {
                quantity = existing.quantity
                servingSizeId = existing.servingSizeId
            }
            remembered != null -> {
                quantity = remembered.quantity
                servingSizeId = remembered.servingSizeId
            }
            else -> {
                quantity = item.lastLoggedQuantity?.toDoubleOrNull()
                servingSizeId = item.lastLoggedServingSizeId
            }
        }
        _uiState.value = _uiState.value.copy(
            itemForQuantityPicker = item,
            quantityPickerInput = quantity?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "100",
            quantityPickerServingSizeId = servingSizeId
        )
    }

    fun dismissQuantityPicker() {
        _uiState.value = _uiState.value.copy(itemForQuantityPicker = null)
    }

    fun updateQuantityPickerInput(value: String) {
        _uiState.value = _uiState.value.copy(quantityPickerInput = value)
    }

    /** Resets quantity to "1" whenever the unit changes -- see
     * createServing's doc comment on this same reasoning. */
    fun updateQuantityPickerServing(servingSizeId: Int?) {
        _uiState.value = _uiState.value.copy(quantityPickerServingSizeId = servingSizeId, quantityPickerInput = "1")
    }

    /** Adds (or updates, if already in the list) the ingredient with
     * whatever quantity/serving is currently set in the picker - stores
     * that quantity/servingSizeId pair as-is (see
     * CreateRecipeIngredientRow's doc comment for why NOT grams). */
    fun confirmQuantityPicker() {
        val state = _uiState.value
        val item = state.itemForQuantityPicker ?: return
        val quantityValue = state.quantityPickerInput.toDoubleOrNull() ?: return
        if (quantityValue <= 0.0) return
        val row = CreateRecipeIngredientRow(item, quantityValue, state.quantityPickerServingSizeId)
        if (row.grams <= 0.0) return

        val withoutExisting = state.ingredients.filterNot { it.item.itemId == item.itemId }
        _uiState.value = state.copy(
            ingredients = withoutExisting + row,
            itemForQuantityPicker = null
        )
    }

    fun removeIngredient(itemId: Int) {
        _uiState.value = _uiState.value.copy(
            ingredients = _uiState.value.ingredients.filterNot { it.item.itemId == itemId },
            itemForQuantityPicker = if (_uiState.value.itemForQuantityPicker?.itemId == itemId) null else _uiState.value.itemForQuantityPicker
        )
    }

    /** Barcode/create flow's finished Item lands here (see
     * AddItemScreen's onUseCreatedItem) -- opens the same quantity
     * picker a search result tap would, rather than silently defaulting
     * to 100g, so a scanned item gets the same "pick how much" step a
     * searched one does. */
    fun addIngredientFromBarcodeFlow(item: Item) {
        openQuantityPicker(item)
    }

    // --- Ingredients phase: create new serving ---

    fun openCreateServingDialog() {
        _uiState.value = _uiState.value.copy(
            showCreateServingDialog = true,
            newServingName = "",
            newServingWeightG = "",
            createServingError = null
        )
    }

    fun dismissCreateServingDialog() {
        _uiState.value = _uiState.value.copy(showCreateServingDialog = false)
    }

    fun updateNewServingName(name: String) {
        _uiState.value = _uiState.value.copy(newServingName = name)
    }

    fun updateNewServingWeightG(weightG: String) {
        _uiState.value = _uiState.value.copy(newServingWeightG = weightG)
    }

    fun createServing() {
        val state = _uiState.value
        val item = state.itemForQuantityPicker ?: return
        val weight = state.newServingWeightG.toDoubleOrNull()
        if (state.newServingName.isBlank() || weight == null || weight <= 0.0) {
            _uiState.value = state.copy(createServingError = "Enter a name and a weight greater than 0")
            return
        }
        _uiState.value = state.copy(isCreatingServing = true, createServingError = null)
        viewModelScope.launch {
            try {
                val updatedItem = ApiClient.service.createServingSize(
                    itemId = item.itemId,
                    name = state.newServingName.trim(),
                    weightG = weight
                )
                val newServing = updatedItem.servingSizes.find {
                    it.name == state.newServingName.trim() && it.weightG.toDoubleOrNull() == weight
                }
                _uiState.value = _uiState.value.copy(
                    isCreatingServing = false,
                    showCreateServingDialog = false,
                    itemForQuantityPicker = updatedItem,
                    quantityPickerServingSizeId = newServing?.id,
                    // Reset to 1, same reasoning as
                    // updateQuantityPickerServing -- otherwise whatever
                    // gram quantity was typed before switching units gets
                    // reinterpreted as a multiplier of the NEW serving's
                    // weight (100 x a 62g protein bar = 6200g), which is
                    // never what was intended (see design discussion).
                    quantityPickerInput = "1"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingServing = false,
                    createServingError = e.message ?: "Couldn't create that serving"
                )
            }
        }
    }

    // --- Save ---

    fun save() {
        val state = _uiState.value
        val servingsValue = state.servings.toDoubleOrNull() ?: return
        if (!state.isSaveValid) return

        _uiState.value = state.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val created = ApiClient.service.createRecipe(
                    RecipeCreateRequest(
                        name = state.name.trim(),
                        recipeType = "recipe",
                        servings = servingsValue,
                        ingredients = state.ingredients.map {
                            RecipeIngredientCreateRequest(
                                itemId = it.item.itemId,
                                servingSizeId = it.servingSizeId,
                                quantity = it.quantity
                            )
                        }
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = false, createdRecipe = created)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = e.message ?: "Couldn't save recipe"
                )
            }
        }
    }

    /** Clears everything back to a blank form -- called when re-entering
     * this mode after a completed save, or switching meals (see the
     * viewModel(key=...) scoping at the call site, which actually
     * creates a fresh instance per meal -- this covers re-entering the
     * SAME meal's Create flow after already finishing one recipe in it). */
    fun reset() {
        searchJob?.cancel()
        _uiState.value = CreateRecipeUiState()
    }
}