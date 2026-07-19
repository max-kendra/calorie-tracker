package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.ItemMacrosUpdateRequest
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.LogCreateRequest
import com.mealtracker.android.network.models.LogFromMealRequest
import com.mealtracker.android.network.models.LogUpdateRequest
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeCreateRequest
import com.mealtracker.android.network.models.RecipeIngredientCreateRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MEAL_DISPLAY_NAMES = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks"
)

/** Which method the Add Item sheet is currently showing - see
 * MealDetailScreen's sheetContent. Search and "Saved" merged into one
 * mode (SEARCH) per earlier design discussion - both showed a search
 * bar and a results list, the only difference was what populated that
 * list before you typed anything, so there's no reason for them to be
 * separate screens. The search bar in SEARCH mode shows recentItems
 * when the query is blank, searchResults once you type.
 *
 * BARCODE now embeds the FULL AddItemScreen flow directly (camera scan
 * -> match/no-match -> product photo -> crop -> label -> form -> save),
 * not a separate simplified quick-scan - that simplified version used
 * to live here (onBarcodeScanned/onGalleryBarcodeResult/
 * barcodeNotFound, now removed) but was fully superseded once the whole
 * flow could be embedded inline (see design discussion: "we want it
 * inside the card", not navigating to a separate screen). */
enum class AddItemSheetMode { SEARCH, BARCODE, CREATE }

/**
 * Flat default for the sheet's "quick log" flows (tap a Saved/Search
 * result, or a barcode match) - no quantity/serving picker yet, so
 * every quick-logged item is recorded as exactly 100g (grams directly,
 * since no serving_size_id is sent - see LoggableEntryBase's docstring
 * on the backend). This is a genuine simplification, not a smart
 * default: a "1 banana" item quick-logged this way will NOT come out to
 * one banana's worth of calories. Users can still get an accurate
 * amount via the full barcode/OCR flow (AddItemScreen), which asks for
 * real quantities - revisit this once a quantity-picker step exists
 * for the quick-log paths too.
 */
private const val QUICK_LOG_QUANTITY_G = 100.0

// How long to wait after the last keystroke before actually firing a
// search request - see updateSearchQuery's doc comment.
private const val SEARCH_DEBOUNCE_MS = 350L

// Same conversion AddItemViewModel uses (EU labels round salt = sodium
// x 2.5). Kept as a separate constant here rather than sharing
// AddItemViewModel's private one since these are two different files/
// ViewModels - see saveItemEdit()'s doc comment.
private const val SALT_TO_SODIUM_RATIO = 2.5

/** "200" instead of "200.0" for whole numbers, but still shows real
 * decimals (e.g. "37.5") when the value actually has one - used
 * anywhere a quantity gets shown/pre-filled as text, per design
 * discussion ("only show whole grams, not fractions"). Not private -
 * MealDetailScreen's logged-items row list uses it too. */
fun formatQuantity(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Remembers what quantity/serving was last used to log a given item,
 * in-memory only (per app session, not persisted) - backs both the
 * "X Cal, Yg" preview in the search list and what the "+" quick-add
 * button actually logs, so the two stay consistent with each other (see
 * design discussion: "we should display kcal and g that would be added
 * if the user pressed plus"). Falls back to 100g/no-serving the first
 * time an item is logged, same as the old flat QUICK_LOG_QUANTITY_G
 * default. */
/** Which kind of thing the search tab is filtering to - see
 * MealDetailUiState.searchFilter's doc comment for why RECIPE/MEAL take
 * a completely different query/results path than ALL/PRODUCT/
 * INGREDIENT. */
enum class SearchFilter(val label: String) {
    ALL("All"), PRODUCT("Product"), INGREDIENT("Ingredient"), RECIPE("Recipe"), MEAL("Meal")
}

data class LoggedAmount(val quantity: Double, val servingSizeId: Int?)

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
    // See requestUsdaSearch()'s doc comment. Non-null (possibly blank)
    // means a jump is pending; carries over whatever the user already
    // typed in this screen's own search box.
    val requestedUsdaSearchQuery: String? = null,
    // Entering the embedded AddItemScreen via "Search USDA" reuses the
    // BARCODE sheetMode slot to render it (see that mode's doc comment)
    // - but that made the Barcode icon incorrectly look "selected" even
    // though the user came from Search, not by tapping Barcode
    // themselves (see design discussion). This tracks that distinction
    // separately, for icon highlighting ONLY - content rendering still
    // goes purely off sheetMode.
    val enteredAddFlowViaUsdaLink: Boolean = false,
    val recentItems: List<Item> = emptyList(),
    val isLoadingRecentItems: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Item> = emptyList(),
    val isSearching: Boolean = false,
    // Filter chips above the search results - ALL/PRODUCT/INGREDIENT
    // search Items (with an optional type= param); RECIPE/MEAL search
    // Recipes instead (recipe_type= param), a completely different
    // result list/quick-log path since Recipes don't have per-100g
    // macros or serving sizes the way Items do.
    val searchFilter: SearchFilter = SearchFilter.ALL,
    val recentRecipes: List<Recipe> = emptyList(),
    val recipeSearchResults: List<Recipe> = emptyList(),
    val isSearchingRecipes: Boolean = false,
    // Same idea as quickLoggingItemId but for the recipe list.
    val quickLoggingRecipeId: Int? = null,
    // Set while a quick-log request for THIS item id is in flight, so
    // the UI can show a per-row spinner instead of a global one.
    val quickLoggingItemId: Int? = null,
    val quickLogError: String? = null,
    // Log detail/edit sheet - fallback for RECIPE-based logs only (see
    // openLogDetail's doc comment for why item-based logs go through
    // itemToLog/ItemLogPageDialog instead, same screen as logging a new
    // item).
    val selectedLog: Log? = null,
    val editQuantityInput: String = "",
    val isSavingLogEdit: Boolean = false,
    val logEditError: String? = null,

    // Opens ItemLogPageDialog - used for BOTH logging a new item
    // (tapping a search result) AND editing an already-logged item
    // (tapping a row in "Logged items"), same screen either way. Which
    // mode it's in is just whether editingLogId is set.
    val itemToLog: Item? = null,
    // Non-null = editing that existing log (confirm PATCHes + can
    // delete); null = logging a new one (confirm POSTs).
    val editingLogId: Int? = null,
    val logQuantityInput: String = "1",
    // null = raw grams; otherwise the chosen ServingSize's id. The
    // dropdown always has "g" as an option alongside whatever named
    // servings the item has (see ServingSize.name - e.g. "slice").
    val logServingSizeId: Int? = null,
    val isLoggingItem: Boolean = false,
    val logItemError: String? = null,
    // See LoggedAmount's doc comment.
    val lastLoggedAmounts: Map<Int, LoggedAmount> = emptyMap(),

    // "Create new serving" - reached from the unit/serving dropdown on
    // the item log page. Nested under itemToLog (only relevant while
    // that page is open).
    val showCreateServingDialog: Boolean = false,
    val newServingName: String = "",
    val newServingWeightG: String = "",
    val isCreatingServing: Boolean = false,
    val createServingError: String? = null,

    // Edit (pencil) button on the item info page - name + macros only.
    // Nested under itemToLog same as the serving-creation state above.
    val showEditItemDialog: Boolean = false,
    val editItemName: String = "",
    val editItemKcal: String = "",
    val editItemProtein: String = "",
    val editItemCarbs: String = "",
    val editItemFat: String = "",
    val editItemFiber: String = "",
    val editItemSugar: String = "",
    val editItemSaturatedFat: String = "",
    // Grams, not mg - same reasoning as AddItemViewModel's saltG100g
    // (food labels show salt, not sodium; converted at the boundary,
    // see saveItemEdit()).
    val editItemSaltG: String = "",
    val isSavingItemEdit: Boolean = false,
    val editItemError: String? = null
)

/**
 * Fetches independently from JournalViewModel (its own /logs +
 * /goals/active calls, scoped to just this one meal/date) rather than
 * receiving data passed through navigation - simpler than threading
 * complex state through nav arguments, and keeps this screen's data
 * fresh if the user navigates back and forth.
 */
class MealDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MealDetailUiState())
    val uiState: StateFlow<MealDetailUiState> = _uiState

    /** Called after something gets ADDED to this meal (quick-add, the
     * quantity picker's confirm, a recipe/meal, or finishing the
     * embedded AddItemScreen flow) - resets the search box and
     * refetches "recent" so the just-added/just-updated item shows up
     * there too (recent items are ordered by updated_at server-side).
     * Previously the search query/results were deliberately preserved
     * across a plain load() (see that function's own doc comment) -
     * that's still correct for load() itself, but specifically after an
     * ADD, per design discussion, the search field should reset and
     * "recent" should reflect the new item, not just stay stale. */
    fun refreshSearchAfterAdd() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            searchResults = emptyList(),
            recipeSearchResults = emptyList()
        )
        loadRecentItems()
    }

    fun load(date: LocalDate, mealType: String) {
        // Preserves sheet state (mode, search query/results, recent
        // items) across this reset - logItemQuickly() calls load()
        // again afterward just to refresh totals/logs, and resetting the
        // whole state back to MealDetailUiState() defaults there would
        // jarringly snap the sheet back to its initial mode/empty state
        // every time someone logs something.
        //
        // isLoading only flips to true when there's nothing on screen
        // yet (first load, or recovering from an error) - otherwise
        // adding/removing/editing an item would blank the whole screen
        // to a spinner for a split second on every single change, since
        // this function gets called again after each of those to
        // refresh totals.
        val alreadyHasData = !_uiState.value.isLoading && _uiState.value.error == null &&
            _uiState.value.date != null
        _uiState.value = _uiState.value.copy(
            isLoading = !alreadyHasData,
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

        loadRecentItems()
    }

    // --- Add Item sheet ---

    fun setSheetMode(mode: AddItemSheetMode) {
        // A deliberate tap on either icon means the user is explicitly
        // choosing that mode - clears the "came from the USDA link"
        // distinction so Barcode's highlight goes back to reflecting
        // sheetMode normally (see enteredAddFlowViaUsdaLink's doc
        // comment).
        _uiState.value = _uiState.value.copy(sheetMode = mode, enteredAddFlowViaUsdaLink = false)
    }

    /** "Can't find it? Search USDA" link at the bottom of the text
     * Search tab - switches to the embedded add-item flow (BARCODE
     * mode/AddItemScreen) and requests it jump straight to USDA_SEARCH
     * rather than starting at SCAN_BARCODE. See design discussion:
     * USDA lookup should be reachable ONLY from text search, not
     * injected into barcode scanning. MealDetailScreen consumes and
     * clears requestedUsdaSearch once it's actually triggered the jump
     * on the embedded AddItemViewModel. */
    fun requestUsdaSearch() {
        _uiState.value = _uiState.value.copy(
            sheetMode = AddItemSheetMode.BARCODE,
            requestedUsdaSearchQuery = _uiState.value.searchQuery,
            enteredAddFlowViaUsdaLink = true
        )
    }

    fun clearUsdaSearchRequest() {
        _uiState.value = _uiState.value.copy(requestedUsdaSearchQuery = null)
    }

    /** "Recent" here means recently added/updated in the catalog (GET
     * /items with no query is already ordered by updated_at desc
     * server-side - see design discussion), NOT recently logged. Was
     * previously /logs/recent-items (recency of LOGGING); switched
     * because the search tab is about finding an item to log, and what
     * you just created/edited is more relevant there than what you
     * happened to eat most recently. */
    /** "Recent" here means recently added/updated in the catalog (GET
     * /items with no query is already ordered by updated_at desc
     * server-side - see design discussion), NOT recently logged. Loads
     * BOTH items and recipes up front (cheap, both lists are small/
     * capped) so switching filters doesn't need a fresh network call
     * just to show the blank-query state. */
    /** "Recent" here means recently added/updated in the catalog (GET
     * /items with no query is already ordered by updated_at desc
     * server-side - see design discussion), NOT recently logged. Now
     * respects the current filter (type=/recipe_type=) - it used to
     * always fetch everything regardless of which filter chip was
     * selected, which is why switching filters while the search box was
     * blank appeared to do nothing (see design discussion: "if I have a
     * list with products and ingredients and select ingredient,
     * everything stays the same" - that's this blank-query "recent"
     * state specifically, non-blank search already filtered correctly). */
    private fun loadRecentItems() {
        val filter = _uiState.value.searchFilter

        if (filter == SearchFilter.RECIPE || filter == SearchFilter.MEAL) {
            val recipeType = if (filter == SearchFilter.RECIPE) "recipe" else "meal"
            viewModelScope.launch {
                try {
                    val recipes = ApiClient.service.searchRecipes(query = null, recipeType = recipeType)
                    _uiState.value = _uiState.value.copy(recentRecipes = recipes)
                } catch (e: Exception) {
                    // Not core functionality - fails quietly.
                }
            }
            return
        }

        val itemType = when (filter) {
            SearchFilter.PRODUCT -> "product"
            SearchFilter.INGREDIENT -> "ingredient"
            else -> null
        }
        _uiState.value = _uiState.value.copy(isLoadingRecentItems = true)
        viewModelScope.launch {
            try {
                val items = ApiClient.service.searchItems(query = null, type = itemType)
                _uiState.value = _uiState.value.copy(isLoadingRecentItems = false, recentItems = items)
            } catch (e: Exception) {
                // Not core functionality - fails quietly to an empty
                // list rather than blocking the sheet from working.
                _uiState.value = _uiState.value.copy(isLoadingRecentItems = false)
            }
        }
    }

    /** ALL/PRODUCT/INGREDIENT switch which type= filter (or none) gets
     * passed to the ITEMS search; RECIPE/MEAL switch to searching
     * RECIPES instead via recipe_type=, a completely different
     * endpoint/result list (see MealDetailUiState.searchFilter's doc
     * comment). Re-runs whatever query is currently typed against the
     * newly-selected filter - or, if the query is blank, refetches
     * "recent" instead (loadRecentItems is filter-aware now too, see
     * its own doc comment for why that refetch didn't used to happen
     * at all). */
    fun updateSearchFilter(filter: SearchFilter) {
        _uiState.value = _uiState.value.copy(searchFilter = filter)
        if (_uiState.value.searchQuery.isBlank()) {
            loadRecentItems()
        } else {
            updateSearchQuery(_uiState.value.searchQuery)
        }
    }

    // Cancelled and relaunched on every keystroke - see updateSearchQuery's
    // debounce below.
    private var searchJob: Job? = null

    /** Debounced now - previously fired a request on EVERY keystroke
     * (see design discussion: "we send a request every single stroke",
     * observed hammering USDA search this way too - see
     * AddItemViewModel.updateUsdaQuery for that side). Cancels any
     * still-pending search and waits SEARCH_DEBOUNCE_MS of no further
     * typing before actually querying, same debounce pattern in both
     * places. The searchQuery== guard in each result handler is now
     * mostly redundant (cancelling the job already prevents a stale
     * response from ever landing) but kept as a harmless extra safety
     * net. */
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        val filter = _uiState.value.searchFilter

        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                searchResults = emptyList(),
                isSearching = false,
                recipeSearchResults = emptyList(),
                isSearchingRecipes = false
            )
            return
        }

        if (filter == SearchFilter.RECIPE || filter == SearchFilter.MEAL) {
            _uiState.value = _uiState.value.copy(isSearchingRecipes = true)
            val recipeType = if (filter == SearchFilter.RECIPE) "recipe" else "meal"
            searchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                try {
                    val results = ApiClient.service.searchRecipes(query = query, recipeType = recipeType)
                    if (_uiState.value.searchQuery == query) {
                        _uiState.value = _uiState.value.copy(isSearchingRecipes = false, recipeSearchResults = results)
                    }
                } catch (e: Exception) {
                    if (_uiState.value.searchQuery == query) {
                        _uiState.value = _uiState.value.copy(isSearchingRecipes = false)
                    }
                }
            }
            return
        }

        val itemType = when (filter) {
            SearchFilter.PRODUCT -> "product"
            SearchFilter.INGREDIENT -> "ingredient"
            else -> null
        }
        _uiState.value = _uiState.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val results = ApiClient.service.searchItems(query = query, type = itemType)
                // Guard against a slower earlier search response landing
                // after a newer one - only apply if the query is still
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

    /** Recipes/meals log by recipe_id with a flat 1-serving default -
     * same "quick add, not precise" tradeoff as logItemQuickly's flat
     * 100g for items (see QUICK_LOG_QUANTITY_G's doc comment). Recipe
     * quantity semantics are "number of servings consumed", so 1 here
     * means one full recipe serving, not "the whole recipe". */
    /** Recipes log atomically (one log entry referencing recipe_id, flat
     * 1-serving default - same "quick add, not precise" tradeoff as
     * logItemQuickly's flat 100g). Meals expand into one log PER
     * INGREDIENT instead (see LogFromMealRequest's doc comment) - each
     * lands individually editable/removable, which is the whole
     * functional distinction between the two (see design discussion). */
    fun logRecipeQuickly(recipe: Recipe) {
        val state = _uiState.value
        val date = state.date ?: return
        if (state.mealType.isEmpty()) return

        _uiState.value = state.copy(quickLoggingRecipeId = recipe.recipeId, quickLogError = null)
        viewModelScope.launch {
            try {
                if (recipe.recipeType == "meal") {
                    ApiClient.service.createLogsFromMeal(
                        LogFromMealRequest(
                            recipeId = recipe.recipeId,
                            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            mealType = state.mealType
                        )
                    )
                } else {
                    ApiClient.service.createLog(
                        LogCreateRequest(
                            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            mealType = state.mealType,
                            recipeId = recipe.recipeId,
                            quantity = 1.0
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(quickLoggingRecipeId = null)
                load(date, state.mealType)
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    quickLoggingRecipeId = null,
                    quickLogError = e.message ?: "Couldn't log that recipe"
                )
            }
        }
    }

    /** Tap-to-log from Saved or Search results - see QUICK_LOG_QUANTITY_G
     * for the flat-100g simplification this currently uses. */
    /** Uses whatever quantity/serving was last used for THIS item (see
     * LoggedAmount), defaulting to 100g the first time. This is what the
     * "+" button in the search list actually logs - kept consistent
     * with the preview text shown next to it (see ItemResultsList). */
    fun logItemQuickly(itemId: Int) {
        val state = _uiState.value
        val date = state.date ?: return
        if (state.mealType.isEmpty()) return

        val remembered = state.lastLoggedAmounts[itemId]
        val quantity = remembered?.quantity ?: QUICK_LOG_QUANTITY_G
        val servingSizeId = remembered?.servingSizeId

        _uiState.value = state.copy(quickLoggingItemId = itemId, quickLogError = null)
        viewModelScope.launch {
            try {
                ApiClient.service.createLog(
                    LogCreateRequest(
                        date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        mealType = state.mealType,
                        itemId = itemId,
                        servingSizeId = servingSizeId,
                        quantity = quantity
                    )
                )
                _uiState.value = _uiState.value.copy(
                    quickLoggingItemId = null,
                    lastLoggedAmounts = _uiState.value.lastLoggedAmounts +
                        (itemId to LoggedAmount(quantity, servingSizeId))
                )
                // Refresh this meal's totals/logs, reset search, and
                // refetch recent items (this item just became the most
                // recent) - see refreshSearchAfterAdd's doc comment.
                load(date, state.mealType)
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    quickLoggingItemId = null,
                    quickLogError = e.message ?: "Couldn't log that item"
                )
            }
        }
    }

    // --- Quantity/serving picker (tapping a search result, not its "+") ---

    /** Called after ItemLogPageDialog's long-press image-change flow
     * (retake/gallery + crop + upload) succeeds. Swaps in the updated
     * Item so the open dialog's hero image refreshes immediately, AND
     * reloads the meal - without that reload, the change genuinely did
     * save (PATCH /items/{id} succeeded), but the "Logged items" list's
     * thumbnails are denormalized onto each Log at fetch time and
     * wouldn't pick up the new image_path until the next unrelated
     * reload, which read exactly like "changing the image doesn't save"
     * even though it had. */
    fun onItemImageUpdated(item: Item) {
        val date = _uiState.value.date
        _uiState.value = _uiState.value.copy(itemToLog = item)
        if (date != null) load(date, _uiState.value.mealType)
    }

    fun openItemQuantityPicker(item: Item) {
        val remembered = _uiState.value.lastLoggedAmounts[item.itemId]
        _uiState.value = _uiState.value.copy(
            itemToLog = item,
            logQuantityInput = remembered?.quantity?.let { formatQuantity(it) } ?: formatQuantity(QUICK_LOG_QUANTITY_G),
            logServingSizeId = remembered?.servingSizeId,
            logItemError = null
        )
    }

    fun dismissItemQuantityPicker() {
        _uiState.value = _uiState.value.copy(itemToLog = null, editingLogId = null, logItemError = null)
    }

    fun updateLogQuantityInput(value: String) {
        _uiState.value = _uiState.value.copy(logQuantityInput = value)
    }

    /** null = switch to raw grams. Resets quantity to "0" whenever the
     * unit changes -- otherwise whatever number was typed for the
     * PREVIOUS unit gets reinterpreted against the new one (e.g. "100"
     * grams becomes "100 slices" = 6200g for a 62g protein bar), which
     * is never what was intended. Forcing back to 0 (rather than
     * silently guessing 1) makes it obvious a fresh quantity needs to
     * be entered for whatever was just selected. */
    fun updateLogServingSize(servingSizeId: Int?) {
        _uiState.value = _uiState.value.copy(logServingSizeId = servingSizeId, logQuantityInput = "0")
    }

    /** Confirms ItemLogPageDialog - POSTs a new log, or PATCHes an
     * existing one if editingLogId is set (see that field's doc
     * comment). Either way, unlike logItemQuickly's flat 100g, this
     * sends whatever quantity+unit the user actually chose. With a
     * serving selected, quantity is a MULTIPLIER of that serving's
     * weight_g (e.g. serving="slice" @ 37.5g, quantity=2 -> the backend
     * computes 75g worth of macros) - see LoggableEntryBase's
     * quantity-semantics doc comment on the backend for why this isn't
     * grams in that case. */
    fun confirmLogItemQuantity() {
        val state = _uiState.value
        val item = state.itemToLog ?: return
        val date = state.date ?: return
        val quantity = state.logQuantityInput.toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _uiState.value = state.copy(logItemError = "Enter a valid quantity")
            return
        }

        _uiState.value = state.copy(isLoggingItem = true, logItemError = null)
        viewModelScope.launch {
            try {
                val editingLogId = state.editingLogId
                if (editingLogId != null) {
                    ApiClient.service.updateLog(
                        editingLogId,
                        LogUpdateRequest(quantity = quantity, servingSizeId = state.logServingSizeId)
                    )
                } else {
                    ApiClient.service.createLog(
                        LogCreateRequest(
                            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            mealType = state.mealType,
                            itemId = item.itemId,
                            servingSizeId = state.logServingSizeId,
                            quantity = quantity
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoggingItem = false,
                    itemToLog = null,
                    editingLogId = null,
                    lastLoggedAmounts = _uiState.value.lastLoggedAmounts +
                        (item.itemId to LoggedAmount(quantity, state.logServingSizeId))
                )
                load(date, state.mealType)
                // Only for the new-log case - editing an existing log's
                // quantity isn't "adding" (see refreshSearchAfterAdd's
                // doc comment).
                if (editingLogId == null) refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoggingItem = false,
                    logItemError = e.message ?: "Couldn't save that log"
                )
            }
        }
    }

    // --- Create new serving (from the item log page's unit dropdown) ---

    fun openCreateServingDialog() {
        _uiState.value = _uiState.value.copy(
            showCreateServingDialog = true,
            newServingName = "",
            newServingWeightG = "",
            createServingError = null
        )
    }

    fun dismissCreateServingDialog() {
        _uiState.value = _uiState.value.copy(showCreateServingDialog = false, createServingError = null)
    }

    fun updateNewServingName(value: String) {
        _uiState.value = _uiState.value.copy(newServingName = value)
    }

    fun updateNewServingWeight(value: String) {
        _uiState.value = _uiState.value.copy(newServingWeightG = value)
    }

    /** Creates the serving, then updates itemToLog with the returned
     * Item (which includes the new serving in serving_sizes) and
     * selects it - so the log page's dropdown immediately reflects it
     * without a separate reload. */
    fun createNewServing() {
        val state = _uiState.value
        val item = state.itemToLog ?: return
        val name = state.newServingName.trim()
        val weightG = state.newServingWeightG.toDoubleOrNull()
        if (name.isEmpty()) {
            _uiState.value = state.copy(createServingError = "Enter a name")
            return
        }
        if (weightG == null || weightG <= 0.0) {
            _uiState.value = state.copy(createServingError = "Enter a valid weight in grams")
            return
        }

        _uiState.value = state.copy(isCreatingServing = true, createServingError = null)
        viewModelScope.launch {
            try {
                val updatedItem = ApiClient.service.createServingSize(item.itemId, name, weightG)
                val newServing = updatedItem.servingSizes.find { it.name == name && it.weightG.toDoubleOrNull() == weightG }
                _uiState.value = _uiState.value.copy(
                    isCreatingServing = false,
                    showCreateServingDialog = false,
                    itemToLog = updatedItem,
                    logServingSizeId = newServing?.id,
                    // Reset to 0, same reasoning as updateLogServingSize
                    // -- otherwise whatever gram quantity was typed
                    // before switching units (e.g. "100") gets
                    // reinterpreted as a multiplier of the NEW serving's
                    // weight (100 x a 62g protein bar = 6200g), which is
                    // never what was intended (see design discussion).
                    logQuantityInput = "0"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingServing = false,
                    createServingError = e.message ?: "Couldn't create that serving"
                )
            }
        }
    }

    // --- Edit item (pencil button on the item info page) ---

    fun openEditItemDialog() {
        val item = _uiState.value.itemToLog ?: return
        _uiState.value = _uiState.value.copy(
            showEditItemDialog = true,
            editItemName = item.name,
            editItemKcal = item.kcal100g ?: "",
            editItemProtein = item.protein100g ?: "",
            editItemCarbs = item.carbs100g ?: "",
            editItemFat = item.fat100g ?: "",
            editItemFiber = item.fiber100g ?: "",
            editItemSugar = item.sugar100g ?: "",
            editItemSaturatedFat = item.saturatedFat100g ?: "",
            // Item stores sodium (mg) - show as salt (g), same
            // conversion/reasoning as AddItemViewModel's form.
            editItemSaltG = item.sodiumMg100g?.toDoubleOrNull()
                ?.let { it / 1000.0 * SALT_TO_SODIUM_RATIO }?.toString() ?: "",
            editItemError = null
        )
    }

    fun dismissEditItemDialog() {
        _uiState.value = _uiState.value.copy(showEditItemDialog = false, editItemError = null)
    }

    fun updateEditItemName(value: String) { _uiState.value = _uiState.value.copy(editItemName = value) }
    fun updateEditItemKcal(value: String) { _uiState.value = _uiState.value.copy(editItemKcal = value) }
    fun updateEditItemProtein(value: String) { _uiState.value = _uiState.value.copy(editItemProtein = value) }
    fun updateEditItemCarbs(value: String) { _uiState.value = _uiState.value.copy(editItemCarbs = value) }
    fun updateEditItemFat(value: String) { _uiState.value = _uiState.value.copy(editItemFat = value) }
    fun updateEditItemFiber(value: String) { _uiState.value = _uiState.value.copy(editItemFiber = value) }
    fun updateEditItemSugar(value: String) { _uiState.value = _uiState.value.copy(editItemSugar = value) }
    fun updateEditItemSaturatedFat(value: String) {
        _uiState.value = _uiState.value.copy(editItemSaturatedFat = value)
    }
    fun updateEditItemSalt(value: String) { _uiState.value = _uiState.value.copy(editItemSaltG = value) }

    fun saveItemEdit() {
        val state = _uiState.value
        val item = state.itemToLog ?: return
        val name = state.editItemName.trim()
        if (name.isEmpty()) {
            _uiState.value = state.copy(editItemError = "Name can't be empty")
            return
        }

        _uiState.value = state.copy(isSavingItemEdit = true, editItemError = null)
        viewModelScope.launch {
            try {
                val updatedItem = ApiClient.service.updateItemMacros(
                    item.itemId,
                    ItemMacrosUpdateRequest(
                        name = name,
                        kcal100g = state.editItemKcal.toDoubleOrNull(),
                        protein100g = state.editItemProtein.toDoubleOrNull(),
                        carbs100g = state.editItemCarbs.toDoubleOrNull(),
                        fat100g = state.editItemFat.toDoubleOrNull(),
                        fiber100g = state.editItemFiber.toDoubleOrNull(),
                        sugar100g = state.editItemSugar.toDoubleOrNull(),
                        saturatedFat100g = state.editItemSaturatedFat.toDoubleOrNull(),
                        // Salt (g) -> sodium (mg): same math as
                        // AddItemViewModel's saveItem().
                        sodiumMg100g = state.editItemSaltG.toDoubleOrNull()?.times(1000.0)?.div(SALT_TO_SODIUM_RATIO)
                    )
                )
                val date = _uiState.value.date
                _uiState.value = _uiState.value.copy(
                    isSavingItemEdit = false,
                    showEditItemDialog = false,
                    itemToLog = updatedItem
                )
                if (date != null) load(date, _uiState.value.mealType)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingItemEdit = false,
                    editItemError = e.message ?: "Couldn't save changes"
                )
            }
        }
    }

    // --- Log detail / edit / delete ---

    /** Item-based logs open the SAME page used to log a new item
     * (ItemLogPageDialog) - fetches the full Item (Log itself only has
     * denormalized name/image, not per-100g macros or serving_sizes)
     * and pre-fills quantity/serving from what's already saved on the
     * log, with editingLogId set so confirming PATCHes instead of
     * POSTing a new one.
     *
     * Recipe-based logs (log.itemId == null) fall back to the simpler
     * selectedLog/AlertDialog flow below - recipes don't have
     * per-100g macros or serving sizes the same way items do, so the
     * unified page doesn't apply to them. Not extended to cover recipes
     * in this pass. */
    fun openLogDetail(log: Log) {
        val itemId = log.itemId
        if (itemId != null) {
            viewModelScope.launch {
                try {
                    val item = ApiClient.service.getItem(itemId)
                    _uiState.value = _uiState.value.copy(
                        itemToLog = item,
                        editingLogId = log.id,
                        logQuantityInput = log.quantity.toDoubleOrNull()?.let { formatQuantity(it) } ?: log.quantity,
                        logServingSizeId = log.servingSizeId,
                        logItemError = null
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        logItemError = e.message ?: "Couldn't load that item"
                    )
                }
            }
            return
        }

        _uiState.value = _uiState.value.copy(
            selectedLog = log,
            editQuantityInput = log.quantity.toDoubleOrNull()?.let { formatQuantity(it) } ?: log.quantity,
            logEditError = null
        )
    }

    fun dismissLogDetail() {
        _uiState.value = _uiState.value.copy(selectedLog = null, logEditError = null)
    }

    fun updateEditQuantityInput(value: String) {
        _uiState.value = _uiState.value.copy(editQuantityInput = value)
    }

    fun saveLogQuantity() {
        val state = _uiState.value
        val log = state.selectedLog ?: return
        val date = state.date ?: return
        val quantity = state.editQuantityInput.toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _uiState.value = state.copy(logEditError = "Enter a valid quantity")
            return
        }

        _uiState.value = state.copy(isSavingLogEdit = true, logEditError = null)
        viewModelScope.launch {
            try {
                ApiClient.service.updateLog(log.id, LogUpdateRequest(quantity = quantity))
                _uiState.value = _uiState.value.copy(isSavingLogEdit = false, selectedLog = null)
                load(date, state.mealType)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingLogEdit = false,
                    logEditError = e.message ?: "Couldn't save"
                )
            }
        }
    }

    /** Used both by the detail sheet's Delete button AND by swiping a
     * row left in the logged-items list - same action either way. */
    fun deleteLog(logId: Int) {
        val state = _uiState.value
        val date = state.date ?: return
        viewModelScope.launch {
            try {
                ApiClient.service.deleteLog(logId)
                if (state.selectedLog?.id == logId) {
                    _uiState.value = _uiState.value.copy(selectedLog = null)
                }
                load(date, state.mealType)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(logEditError = e.message ?: "Couldn't delete")
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
     * directly in grams (no serving_size_id) are included - converting
     * a serving-size-based quantity to grams needs an extra lookup
     * (the ServingSize's weight_g) that isn't fetched here. Recipe-based
     * logs are also skipped, since recipe_ingredients can only reference
     * items, not other recipes. Both are real gaps, not silent bugs -
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
                // Otherwise the newly-saved meal doesn't show up in
                // search/recent until something unrelated happens to
                // trigger a reload (e.g. switching filters back and
                // forth) - every other add-flow in this ViewModel
                // (logItemQuickly, confirmLogItemQuantity, etc) already
                // calls this after a successful save; this one just
                // hadn't been wired up to do the same.
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingMeal = false,
                    saveMealError = e.message ?: "Failed to save meal"
                )
            }
        }
    }
}