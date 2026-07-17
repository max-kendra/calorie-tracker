package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.LogCreateRequest
import com.mealtracker.android.network.models.RecipeCreateRequest
import com.mealtracker.android.network.models.RecipeIngredientCreateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MEAL_DISPLAY_NAMES = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks"
)

/** Which method the Add Item sheet is currently showing -- see
 * MealDetailScreen's sheetContent. Search and "Saved" merged into one
 * mode (SEARCH) per design discussion -- both showed a search bar and a
 * results list, the only difference was what populated that list before
 * you typed anything, so there's no reason for them to be separate
 * screens. The search bar in SEARCH mode shows recentItems when the
 * query is blank, searchResults once you type. */
enum class AddItemSheetMode { SEARCH, BARCODE }

/**
 * Flat default for the sheet's "quick log" flows (tap a Saved/Search
 * result, or a barcode match) -- no quantity/serving picker yet, so
 * every quick-logged item is recorded as exactly 100g (grams directly,
 * since no serving_size_id is sent -- see LoggableEntryBase's docstring
 * on the backend). This is a genuine simplification, not a smart
 * default: a "1 banana" item quick-logged this way will NOT come out to
 * one banana's worth of calories. Users can still get an accurate
 * amount via the full barcode/OCR flow (AddItemScreen), which asks for
 * real quantities -- revisit this once a quantity-picker step exists
 * for the quick-log paths too.
 */
private const val QUICK_LOG_QUANTITY_G = 100.0

data class MealDetailUiState(
    val isLoading: Boolean = true,
    val date: LocalDate? = null,
    val mealType: String = "",
    val displayName: String = "",
    val logs: List<Log> = emptyList(),
    val eatenKcal: Int = 0,
    val goalKcal: Int = 0,
    val eatenFat: Int = 0,
    val goalFat: Int = 0,
    val eatenProtein: Int = 0,
    val goalProtein: Int = 0,
    val eatenCarbs: Int = 0,
    val goalCarbs: Int = 0,
    val eatenFiber: Int = 0,
    val goalFiber: Int = 0,
    val error: String? = null,
    // "Save this meal" (star icon) state
    val showSaveMealDialog: Boolean = false,
    val mealNameInput: String = "",
    val isSavingMeal: Boolean = false,
    val saveMealError: String? = null,
    val saveMealSuccess: Boolean = false,
    // Add Item sheet state
    val sheetMode: AddItemSheetMode = AddItemSheetMode.SEARCH,
    val recentItems: List<Item> = emptyList(),
    val isLoadingRecentItems: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Item> = emptyList(),
    val isSearching: Boolean = false,
    // Set while a quick-log request for THIS item id is in flight, so
    // the UI can show a per-row spinner instead of a global one.
    val quickLoggingItemId: Int? = null,
    val quickLogError: String? = null,
    // Barcode-in-sheet: set when a scanned barcode doesn't match any
    // known item -- the sheet shows this + a way to fall back to the
    // full scan/OCR flow (AddItemScreen), since that path isn't
    // rebuilt inline yet.
    val barcodeNotFound: String? = null
)

/**
 * Fetches independently from JournalViewModel (its own /logs +
 * /goals/active calls, scoped to just this one meal/date) rather than
 * receiving data passed through navigation -- simpler than threading
 * complex state through nav arguments, and keeps this screen's data
 * fresh if the user navigates back and forth.
 */
class MealDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MealDetailUiState())
    val uiState: StateFlow<MealDetailUiState> = _uiState

    fun load(date: LocalDate, mealType: String) {
        // Preserves sheet state (mode, search query/results, recent
        // items) across this reset -- logItemQuickly() calls load()
        // again afterward just to refresh totals/logs, and resetting the
        // whole state back to MealDetailUiState() defaults there would
        // jarringly snap the sheet back to its initial mode/empty state
        // every time someone logs something.
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            date = date,
            mealType = mealType,
            displayName = MEAL_DISPLAY_NAMES[mealType] ?: mealType,
            error = null
        )

        viewModelScope.launch {
            try {
                val dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val logs = ApiClient.service.getLogs(dateString, mealType)
                val goal = ApiClient.service.getActiveGoal()
                val goalTotals = goal.mealSplits.find { it.mealType == mealType }?.computedTotals

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    logs = logs,
                    eatenKcal = logs.sumOf { it.kcalLogged },
                    goalKcal = goalTotals?.kcal ?: 0,
                    eatenFat = logs.sumOf { it.fatGLogged },
                    goalFat = goalTotals?.fatG ?: 0,
                    eatenProtein = logs.sumOf { it.proteinGLogged },
                    goalProtein = goalTotals?.proteinG ?: 0,
                    eatenCarbs = logs.sumOf { it.carbsGLogged },
                    goalCarbs = goalTotals?.carbsG ?: 0,
                    eatenFiber = logs.sumOf { it.fiberGLogged },
                    goalFiber = goalTotals?.fiberG ?: 0
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }

        loadRecentItems(mealType)
    }

    // ----- Add Item sheet -----

    fun setSheetMode(mode: AddItemSheetMode) {
        _uiState.value = _uiState.value.copy(sheetMode = mode, barcodeNotFound = null)
    }

    private fun loadRecentItems(mealType: String) {
        _uiState.value = _uiState.value.copy(isLoadingRecentItems = true)
        viewModelScope.launch {
            try {
                val items = ApiClient.service.getRecentItems(mealType = mealType)
                _uiState.value = _uiState.value.copy(isLoadingRecentItems = false, recentItems = items)
            } catch (e: Exception) {
                // Not core functionality -- fails quietly to an empty
                // list rather than blocking the sheet from working.
                _uiState.value = _uiState.value.copy(isLoadingRecentItems = false)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true)
        viewModelScope.launch {
            try {
                val results = ApiClient.service.searchItems(query = query)
                // Guard against a slower earlier search response landing
                // after a newer one -- only apply if the query is still
                // current.
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearching = false, searchResults = results)
                }
            } catch (e: Exception) {
                if (_uiState.value.searchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearching = false)
                }
            }
        }
    }

    /** Tap-to-log from Saved or Search results -- see QUICK_LOG_QUANTITY_G
     * for the flat-100g simplification this currently uses. */
    fun logItemQuickly(itemId: Int) {
        val state = _uiState.value
        val date = state.date ?: return
        if (state.mealType.isEmpty()) return

        _uiState.value = state.copy(quickLoggingItemId = itemId, quickLogError = null)
        viewModelScope.launch {
            try {
                ApiClient.service.createLog(
                    LogCreateRequest(
                        date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        mealType = state.mealType,
                        itemId = itemId,
                        quantity = QUICK_LOG_QUANTITY_G
                    )
                )
                _uiState.value = _uiState.value.copy(quickLoggingItemId = null)
                // Refresh this meal's totals/logs AND the recent-items
                // ordering (this item just became the most recent).
                load(date, state.mealType)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    quickLoggingItemId = null,
                    quickLogError = e.message ?: "Couldn't log that item"
                )
            }
        }
    }

    /** Called when the in-sheet barcode scanner (LiveBarcodeScannerView)
     * detects a code -- looks it up and quick-logs it if there's a
     * match; otherwise surfaces barcodeNotFound so the sheet can offer
     * the full scan/OCR flow as a fallback. */
    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            try {
                val item = ApiClient.service.getItemByBarcode(barcode)
                logItemQuickly(item.itemId)
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    _uiState.value = _uiState.value.copy(
                        barcodeNotFound = "No item found for that barcode yet."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        barcodeNotFound = e.message() ?: "Lookup failed"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(barcodeNotFound = e.message ?: "Lookup failed")
            }
        }
    }

    fun openSaveMealDialog() {
        _uiState.value = _uiState.value.copy(showSaveMealDialog = true, mealNameInput = "")
    }

    fun dismissSaveMealDialog() {
        _uiState.value = _uiState.value.copy(showSaveMealDialog = false)
    }

    fun updateMealNameInput(value: String) {
        _uiState.value = _uiState.value.copy(mealNameInput = value)
    }

    /**
     * Snapshots the currently-logged items into a new Recipe with
     * recipe_type="meal" (see backend design doc: a saved Meal is just a
     * recipe with servings=1, editable afterward like any recipe).
     *
     * LIMITATION: only logs that reference a real item AND were logged
     * directly in grams (no serving_size_id) are included -- converting
     * a serving-size-based quantity to grams needs an extra lookup
     * (the ServingSize's weight_g) that isn't fetched here. Recipe-based
     * logs are also skipped, since recipe_ingredients can only reference
     * items, not other recipes. Both are real gaps, not silent bugs --
     * worth fixing if this turns out to matter in practice once logging
     * itself is built (this screen currently has no way to create logs
     * yet, so `logs` will typically be empty regardless).
     */
    fun saveAsMeal() {
        val state = _uiState.value
        val name = state.mealNameInput.trim()
        if (name.isEmpty()) return

        val ingredients = state.logs
            .filter { it.itemId != null && it.servingSizeId == null }
            .mapNotNull { log ->
                val itemId = log.itemId ?: return@mapNotNull null
                val grams = log.quantity.toDoubleOrNull() ?: return@mapNotNull null
                RecipeIngredientCreateRequest(itemId = itemId, quantityG = grams)
            }

        _uiState.value = state.copy(isSavingMeal = true, saveMealError = null)

        viewModelScope.launch {
            try {
                ApiClient.service.createRecipe(
                    RecipeCreateRequest(name = name, recipeType = "meal", servings = 1.0, ingredients = ingredients)
                )
                _uiState.value = _uiState.value.copy(
                    isSavingMeal = false,
                    saveMealSuccess = true,
                    showSaveMealDialog = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingMeal = false,
                    saveMealError = e.message ?: "Failed to save meal"
                )
            }
        }
    }
}