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

/** One ingredient added to the recipe being built -- quantityG is kept
 * as editable text (same reasoning as elsewhere in this app: an empty
 * or partially-typed field like "10" while backspacing to "100" needs
 * to be representable, which a Double can't do). */
data class CreateRecipeIngredientRow(
    val item: Item,
    val quantityG: String = "100"
)

data class CreateRecipeUiState(
    val name: String = "",
    val servings: String = "1",
    val ingredients: List<CreateRecipeIngredientRow> = emptyList(),
    val ingredientSearchQuery: String = "",
    val ingredientSearchResults: List<Item> = emptyList(),
    val isSearchingIngredients: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    // Non-null once the recipe has been created -- the screen shows a
    // confirmation with "log to this meal" / "done" instead of the
    // build form once this is set, rather than a separate phase enum
    // (the created recipe itself IS the signal that we're done building).
    val createdRecipe: Recipe? = null
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
            (servings.toDoubleOrNull() ?: 0.0) > 0.0 &&
            ingredients.isNotEmpty() &&
            ingredients.all { (it.quantityG.toDoubleOrNull() ?: 0.0) > 0.0 }
}

/**
 * Backs the new "Create" method on the meal-detail add sheet (see
 * design discussion: a third button alongside Search/Barcode, for
 * building a brand-new recipe rather than logging an existing item).
 * Scoped per (date, mealType) the same way AddItemViewModel is, via
 * viewModel(key = ...) at the call site -- see MealDetailScreen's
 * CREATE branch -- so switching meals or re-entering after a save
 * starts a fresh build rather than resuming a half-finished one from
 * elsewhere.
 *
 * The backend already fully supported recipe creation (POST /recipes
 * with an embedded ingredients list) before this screen existed -- this
 * is purely a client-side gap being filled, not new backend work.
 */
class CreateRecipeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CreateRecipeUiState())
    val uiState: StateFlow<CreateRecipeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateServings(servings: String) {
        _uiState.value = _uiState.value.copy(servings = servings)
    }

    fun updateIngredientSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(ingredientSearchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                ingredientSearchResults = emptyList(),
                isSearchingIngredients = false
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(INGREDIENT_SEARCH_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isSearchingIngredients = true)
            try {
                val results = ApiClient.service.searchItems(query = query)
                _uiState.value = _uiState.value.copy(
                    isSearchingIngredients = false,
                    ingredientSearchResults = results
                )
            } catch (e: Exception) {
                // Best-effort -- an empty result list reads fine as
                // "no matches" even on a network hiccup, no separate
                // error state needed for what's a secondary search
                // inside a bigger flow.
                _uiState.value = _uiState.value.copy(
                    isSearchingIngredients = false,
                    ingredientSearchResults = emptyList()
                )
            }
        }
    }

    fun addIngredient(item: Item) {
        val current = _uiState.value
        if (current.ingredients.any { it.item.itemId == item.itemId }) return
        _uiState.value = current.copy(
            ingredients = current.ingredients + CreateRecipeIngredientRow(item),
            ingredientSearchQuery = "",
            ingredientSearchResults = emptyList()
        )
    }

    fun updateIngredientQuantity(itemId: Int, quantityG: String) {
        _uiState.value = _uiState.value.copy(
            ingredients = _uiState.value.ingredients.map {
                if (it.item.itemId == itemId) it.copy(quantityG = quantityG) else it
            }
        )
    }

    fun removeIngredient(itemId: Int) {
        _uiState.value = _uiState.value.copy(
            ingredients = _uiState.value.ingredients.filterNot { it.item.itemId == itemId }
        )
    }

    fun save() {
        val state = _uiState.value
        val servingsValue = state.servings.toDoubleOrNull() ?: return
        if (!state.isValid) return

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
                                quantityG = it.quantityG.toDoubleOrNull() ?: 0.0
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