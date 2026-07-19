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

/** One ingredient added to the recipe being built. */
data class CreateRecipeIngredientRow(
    val item: Item,
    val quantityG: Double
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

    fun openQuantityPicker(item: Item) {
        val existing = _uiState.value.ingredients.find { it.item.itemId == item.itemId }
        _uiState.value = _uiState.value.copy(
            itemForQuantityPicker = item,
            quantityPickerInput = existing?.quantityG?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "100",
            quantityPickerServingSizeId = null
        )
    }

    fun dismissQuantityPicker() {
        _uiState.value = _uiState.value.copy(itemForQuantityPicker = null)
    }

    fun updateQuantityPickerInput(value: String) {
        _uiState.value = _uiState.value.copy(quantityPickerInput = value)
    }

    fun updateQuantityPickerServing(servingSizeId: Int?) {
        _uiState.value = _uiState.value.copy(quantityPickerServingSizeId = servingSizeId)
    }

    /** Adds (or updates, if already in the list) the ingredient with
     * whatever quantity/serving is currently set in the picker -- mirrors
     * LoggableEntryBase's quantity semantics (quantity is a multiplier of
     * the selected serving's weight, or raw grams with no serving
     * selected) exactly, same as ItemQuantityDialog computes for the
     * live preview. */
    fun confirmQuantityPicker() {
        val state = _uiState.value
        val item = state.itemForQuantityPicker ?: return
        val quantityValue = state.quantityPickerInput.toDoubleOrNull() ?: return
        val serving = item.servingSizes.find { it.id == state.quantityPickerServingSizeId }
        val grams = if (serving != null) {
            quantityValue * (serving.weightG.toDoubleOrNull() ?: return)
        } else {
            quantityValue
        }
        if (grams <= 0.0) return

        val withoutExisting = state.ingredients.filterNot { it.item.itemId == item.itemId }
        _uiState.value = state.copy(
            ingredients = withoutExisting + CreateRecipeIngredientRow(item, grams),
            itemForQuantityPicker = null,
            ingredientSearchQuery = "",
            ingredientSearchResults = emptyList()
        )
    }

    fun removeIngredient(itemId: Int) {
        _uiState.value = _uiState.value.copy(
            ingredients = _uiState.value.ingredients.filterNot { it.item.itemId == itemId }
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
                    // Reset to 1 -- otherwise whatever gram quantity was
                    // typed before switching units gets reinterpreted as
                    // a multiplier of the NEW serving's weight (100 x a
                    // 62g protein bar = 6200g), which is never what was
                    // intended (see design discussion).
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
                                quantityG = it.quantityG
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