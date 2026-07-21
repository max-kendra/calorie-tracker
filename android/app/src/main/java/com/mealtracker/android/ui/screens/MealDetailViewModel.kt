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
import com.mealtracker.android.network.models.RecipeDetail
import com.mealtracker.android.network.models.RecipeIngredient
import com.mealtracker.android.network.models.RecipeUpdateRequest
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
    // Recipe/meal info screen -- tapping a recipe/meal row (not its "+")
    // opens this instead of silently quick-logging, same reasoning as
    // items having ItemLogPageDialog (see design discussion: "recipes
    // and meals not opening their info screens" was a real gap, tapping
    // used to just call the same quick-add the "+" button does).
    val recipeToView: RecipeDetail? = null,
    val isLoadingRecipeDetail: Boolean = false,
    val recipeDetailError: String? = null,
    // Only meaningful for recipe_type="recipe" -- a "meal" logs its
    // originally-captured per-ingredient quantities as-is (see
    // LogFromMealRequest's doc comment), there's no "how many servings"
    // concept to adjust for that type.
    val recipeLogQuantityInput: String = "1",
    val isLoggingRecipeDetail: Boolean = false,
    // Non-null = this info screen was opened by tapping an ALREADY-
    // LOGGED recipe (see openLogDetail), meaning recipeLogQuantityInput
    // is THIS SPECIFIC LOG's quantity, and confirming PATCHes that one
    // log rather than creating a new one -- same "editing this instance
    // vs logging fresh" distinction ItemLogPageDialog already has via
    // editingLogId, applied here for recipes (see design discussion:
    // "we should be able to edit that recipe from there (that instance
    // and the global recipe, but not past instances)"). A "meal" can
    // never reach this, since meal logs always expand into per-
    // ingredient item logs (recipe_id is never set for those) -- only
    // an actual recipe_type="recipe" log references a recipe directly.
    val recipeLogInstanceId: Int? = null,

    // Edit mode (pencil button) on the recipe info screen -- name and
    // servings only, same "edit metadata separately from ingredients"
    // split the backend already has (RecipeUpdate vs the ingredient
    // endpoints).
    val isEditingRecipe: Boolean = false,
    val editRecipeName: String = "",
    val editRecipeServings: String = "",
    val isSavingRecipeEdit: Boolean = false,
    val recipeEditError: String? = null,

    // Delete recipe -- small red text + confirm, same pattern as
    // deleting a logged item elsewhere in the app.
    val showDeleteRecipeConfirm: Boolean = false,
    val isDeletingRecipe: Boolean = false,

    // Add/edit-ingredient search (edit mode only) -- genuinely the same
    // behavior as every other search list in this app (recent items
    // shown blank-query, debounced search once typing starts), not a
    // simplified one-off -- reuses the same shared ItemResultsList/
    // ItemQuantityDialog components CreateRecipeScreen's own ingredient
    // picker uses.
    val ingredientSearchQuery: String = "",
    val ingredientSearchResults: List<Item> = emptyList(),
    val isSearchingIngredients: Boolean = false,
    val recentIngredientItems: List<Item> = emptyList(),
    val isLoadingRecentIngredientItems: Boolean = false,
    val itemForIngredientPicker: Item? = null,
    val ingredientQuantityInput: String = "100",
    val ingredientServingSizeId: Int? = null,
    val isAddingIngredient: Boolean = false,
    val addIngredientError: String? = null,
    // Non-null = the picker is editing THIS existing ingredient (tapped
    // from the ingredient list) rather than adding a new one -- set by
    // openIngredientEdit, cleared whenever the picker closes. Changes
    // confirmAddIngredient's PATCH-vs-POST behavior and shows the
    // Remove option in ItemQuantityDialog.
    val editingIngredientItemId: Int? = null,

    // Tap-hero-to-change-image (recipe/meal info screen) -- same
    // camera/gallery/crop/upload pipeline as ItemLogPageDialog's own
    // image change, just targeting the recipe's image_path instead of
    // an item's.
    val isUploadingRecipeImage: Boolean = false,
    val recipeImageError: String? = null,
    // Set while a quick-log request for THIS item id is in flight, so
    // the UI can show a per-row spinner instead of a global one.
    val quickLoggingItemId: Int? = null,
    val quickLogError: String? = null,
    // Surfaces a failure fetching recipes/meals for the ALL filter --
    // this used to fail silently (a bare comment saying "not core
    // functionality"), which meant a genuine failure looked identical
    // to "there are just no recipes to show", and was completely
    // undiagnosable from the UI (see design discussion: "this is
    // genuinely infuriating, why is it so hard to just aggregate the
    // recipe and item tables... it's only items in there again" - the
    // actual bug, whatever it turns out to be, was invisible because of
    // this silent catch, not necessarily the merge logic itself).
    val allFilterRecipeError: String? = null,

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
    // Manual override for the origin-based "countable sugar" heuristic
    // (see design discussion: "my third highest ranking added sugar
    // source is frozen berry mix... this is silly"). Three-state:
    // null = use the origin heuristic (default), true/false = force
    // count/exclude regardless of origin.
    val editItemCountsAsAddedSugar: Boolean? = null,
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
     * embedded AddItemScreen flow) - refreshes whatever's currently
     * shown so it reflects the change (recent items reordering by
     * last-logged, a quick-added item's fresh preview amount, etc),
     * WITHOUT clearing the user's typed search query or knocking them
     * back to the blank-query "recent" view.
     *
     * Previously this reset searchQuery to "" unconditionally after
     * every single add, which threw away whatever the user had typed
     * and navigated them away from the results they were just looking
     * at (see design discussion: "adding an item to a meal should not
     * reset the search and should instead keep us where we are"). If a
     * search is in progress, re-runs that same query instead of
     * clearing it; only the blank-query "recent" case reloads recent
     * items directly. Clearing the search box is now an explicit, opt-
     * in action (the search field's own "x" button) rather than
     * something that happens as a side effect of adding something. */
    fun refreshSearchAfterAdd() {
        val query = _uiState.value.searchQuery
        if (query.isBlank()) {
            loadRecentItems()
        } else {
            updateSearchQuery(query)
        }
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

        // ALL also fetches recipes+meals (recipeType = null, so both)
        // alongside items below, rather than exclusively going through
        // the items endpoint the way PRODUCT/INGREDIENT do - otherwise
        // "All" wasn't actually all, recipes/meals only ever showed up
        // by specifically switching to the Recipe or Meal tab (see
        // design discussion: "they only show up if we filter for
        // recipes... not with the rest of the items when it's set to
        // show all").
        if (filter == SearchFilter.ALL) {
            viewModelScope.launch {
                try {
                    val recipes = ApiClient.service.searchRecipes(query = null, recipeType = null)
                    _uiState.value = _uiState.value.copy(recentRecipes = recipes, allFilterRecipeError = null)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        allFilterRecipeError = "Couldn't load recipes: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
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
    // Separate from searchJob - when filter is ALL, item search and
    // recipe search run in parallel (see updateSearchQuery), each
    // needing its own debounce/cancel-on-next-keystroke rather than
    // fighting over one shared job.
    private var allFilterRecipeSearchJob: Job? = null

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
        allFilterRecipeSearchJob?.cancel()

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

        // ALL also searches recipes+meals (recipeType = null) IN
        // PARALLEL with the items search below, same reasoning as
        // loadRecentItems's own ALL handling - otherwise typing a
        // search under "All" would never surface a matching recipe/meal
        // at all, only items.
        if (filter == SearchFilter.ALL) {
            _uiState.value = _uiState.value.copy(isSearchingRecipes = true, allFilterRecipeError = null)
            allFilterRecipeSearchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                try {
                    val results = ApiClient.service.searchRecipes(query = query, recipeType = null)
                    if (_uiState.value.searchQuery == query) {
                        _uiState.value = _uiState.value.copy(isSearchingRecipes = false, recipeSearchResults = results)
                    }
                } catch (e: Exception) {
                    if (_uiState.value.searchQuery == query) {
                        _uiState.value = _uiState.value.copy(
                            isSearchingRecipes = false,
                            allFilterRecipeError = "Couldn't search recipes: ${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                }
            }
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

    // --- Recipe/meal info screen (tapping a row, not its "+") ---

    /** Fetches full detail (ingredients, totals) for a tapped recipe/meal
     * -- the search/recent list's own Recipe model doesn't carry enough
     * for this, see RecipeDetail's doc comment. */
    fun openRecipeDetail(recipeId: Int) {
        _uiState.value = _uiState.value.copy(
            isLoadingRecipeDetail = true,
            recipeDetailError = null,
            recipeLogQuantityInput = "1"
        )
        viewModelScope.launch {
            try {
                val detail = ApiClient.service.getRecipe(recipeId)
                _uiState.value = _uiState.value.copy(isLoadingRecipeDetail = false, recipeToView = detail)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecipeDetail = false,
                    recipeDetailError = e.message ?: "Couldn't load that recipe"
                )
            }
        }
    }

    fun dismissRecipeDetail() {
        _uiState.value = _uiState.value.copy(recipeToView = null, recipeDetailError = null, recipeLogInstanceId = null)
    }

    fun updateRecipeLogQuantityInput(value: String) {
        _uiState.value = _uiState.value.copy(recipeLogQuantityInput = value)
    }

    /** Confirms logging from the info screen -- same recipe-vs-meal
     * branching as logRecipeQuickly, except a "recipe" here logs
     * whatever quantity (servings) was actually chosen on this screen,
     * rather than logRecipeQuickly's flat 1-serving default. A "meal"
     * still has no quantity to adjust (see recipeLogQuantityInput's doc
     * comment on MealDetailUiState), so ignores the input entirely and
     * behaves identically to logRecipeQuickly for that type. */
    /** Confirms the quantity field at the bottom of the recipe info
     * screen -- branches on whether recipeLogInstanceId is set (see
     * that field's doc comment): if so, this is editing an ALREADY-
     * LOGGED instance's quantity (PATCH), not logging a new one (POST/
     * meal-expansion). A "meal" can never reach the instance-edit
     * branch (see recipeLogInstanceId's doc comment on why), so the
     * meal-expansion path here is only ever hit when logging fresh. */
    fun confirmRecipeLog() {
        val state = _uiState.value
        val recipe = state.recipeToView ?: return
        val date = state.date ?: return
        if (state.mealType.isEmpty()) return

        val quantity = state.recipeLogQuantityInput.toDoubleOrNull()
        if (recipe.recipeType != "meal" && (quantity == null || quantity <= 0.0)) {
            _uiState.value = state.copy(recipeDetailError = "Enter a valid quantity")
            return
        }

        val instanceId = state.recipeLogInstanceId
        _uiState.value = state.copy(isLoggingRecipeDetail = true, recipeDetailError = null)
        viewModelScope.launch {
            try {
                if (instanceId != null) {
                    ApiClient.service.updateLog(instanceId, LogUpdateRequest(quantity = quantity!!))
                } else if (recipe.recipeType == "meal") {
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
                            quantity = quantity!!
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoggingRecipeDetail = false,
                    recipeToView = null,
                    recipeLogInstanceId = null
                )
                load(date, state.mealType)
                // Same fix as confirmLogItemQuantity - refresh
                // regardless of new-vs-edit, since editing an existing
                // recipe log's quantity now also bumps the recipe's own
                // last_logged_at server-side (see update_log on the
                // backend), and the recipe search/recent list needs to
                // reflect that too, not just brand-new logs.
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoggingRecipeDetail = false,
                    recipeDetailError = e.message ?: "Couldn't log that recipe"
                )
            }
        }
    }

    /** Deletes THIS SPECIFIC LOGGED INSTANCE (see recipeLogInstanceId's
     * doc comment) -- distinct from confirmDeleteRecipe, which deletes
     * the recipe/meal itself (and every instance it was ever logged
     * as). Reuses deleteLog, same as swiping a row in the logged-items
     * list. */
    fun deleteRecipeLogInstance() {
        val instanceId = _uiState.value.recipeLogInstanceId ?: return
        deleteLog(instanceId)
        _uiState.value = _uiState.value.copy(recipeToView = null, recipeLogInstanceId = null)
    }

    // --- Edit recipe (pencil button) ---

    fun openRecipeEdit() {
        val recipe = _uiState.value.recipeToView ?: return
        _uiState.value = _uiState.value.copy(
            isEditingRecipe = true,
            editRecipeName = recipe.name,
            editRecipeServings = recipe.servings,
            recipeEditError = null
        )
        loadRecentIngredientItems()
    }

    fun dismissRecipeEdit() {
        _uiState.value = _uiState.value.copy(isEditingRecipe = false, recipeEditError = null)
    }

    fun updateEditRecipeName(value: String) {
        _uiState.value = _uiState.value.copy(editRecipeName = value)
    }

    fun updateEditRecipeServings(value: String) {
        _uiState.value = _uiState.value.copy(editRecipeServings = value)
    }

    fun saveRecipeEdit() {
        val state = _uiState.value
        val recipe = state.recipeToView ?: return
        val name = state.editRecipeName.trim()
        if (name.isEmpty()) {
            _uiState.value = state.copy(recipeEditError = "Name can't be empty")
            return
        }
        // Meals are always exactly 1 serving (see recipeLogQuantityInput's
        // doc comment) -- servings isn't editable for that type, so this
        // is left null, which (with ApiClient's default encodeDefaults
        // behavior) omits the field from the request entirely rather
        // than sending it - the backend's exclude_unset=True then leaves
        // the existing value untouched, same "omit = don't change"
        // convention every partial-update endpoint in this app relies on.
        val servings = if (recipe.recipeType == "meal") null else state.editRecipeServings.toDoubleOrNull()
        if (recipe.recipeType != "meal" && (servings == null || servings <= 0.0)) {
            _uiState.value = state.copy(recipeEditError = "Enter a valid number of servings")
            return
        }

        _uiState.value = state.copy(isSavingRecipeEdit = true, recipeEditError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(
                    recipe.recipeId,
                    RecipeUpdateRequest(name = name, servings = servings)
                )
                _uiState.value = _uiState.value.copy(
                    isSavingRecipeEdit = false,
                    isEditingRecipe = false,
                    recipeToView = updated
                )
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingRecipeEdit = false,
                    recipeEditError = e.message ?: "Couldn't save changes"
                )
            }
        }
    }

    // --- Delete recipe ---

    fun requestDeleteRecipe() {
        _uiState.value = _uiState.value.copy(showDeleteRecipeConfirm = true)
    }

    fun dismissDeleteRecipeConfirm() {
        _uiState.value = _uiState.value.copy(showDeleteRecipeConfirm = false)
    }

    fun confirmDeleteRecipe() {
        val recipe = _uiState.value.recipeToView ?: return
        _uiState.value = _uiState.value.copy(isDeletingRecipe = true)
        viewModelScope.launch {
            try {
                ApiClient.service.deleteRecipe(recipe.recipeId)
                _uiState.value = _uiState.value.copy(
                    isDeletingRecipe = false,
                    showDeleteRecipeConfirm = false,
                    recipeToView = null
                )
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeletingRecipe = false,
                    showDeleteRecipeConfirm = false,
                    recipeDetailError = e.message ?: "Couldn't delete that recipe"
                )
            }
        }
    }

    // --- Add/edit ingredient (edit mode) -- reuses ItemResultsList/
    // ItemQuantityDialog, same shared components CreateRecipeScreen's
    // ingredient picker already uses, rather than a second search UI.
    // Genuinely matches how every other search list in this app
    // behaves: recent items shown blank-query, debounced search once
    // typing starts -- not a simplified one-off that only searches. ---

    private var ingredientSearchJob: Job? = null

    fun loadRecentIngredientItems() {
        _uiState.value = _uiState.value.copy(isLoadingRecentIngredientItems = true)
        viewModelScope.launch {
            try {
                val items = ApiClient.service.searchItems(query = null, type = null)
                _uiState.value = _uiState.value.copy(isLoadingRecentIngredientItems = false, recentIngredientItems = items)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingRecentIngredientItems = false)
            }
        }
    }

    fun updateIngredientSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(ingredientSearchQuery = query)
        ingredientSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(ingredientSearchResults = emptyList(), isSearchingIngredients = false)
            return
        }
        _uiState.value = _uiState.value.copy(isSearchingIngredients = true)
        ingredientSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val results = ApiClient.service.searchItems(query = query, type = null)
                if (_uiState.value.ingredientSearchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearchingIngredients = false, ingredientSearchResults = results)
                }
            } catch (e: Exception) {
                if (_uiState.value.ingredientSearchQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearchingIngredients = false)
                }
            }
        }
    }

    /** Opened by tapping a search/recent result -- adding a NEW
     * ingredient. See openIngredientEdit for tapping an EXISTING one
     * already in the recipe instead. Defaults to whatever this item was
     * last logged/added with (see lastLoggedAmounts' doc comment),
     * falling back to a flat 100g only if it's never been logged at
     * all -- previously always defaulted to 100g regardless (see
     * design discussion: "each time i go to make a new meal, it's 100g
     * again"). */
    fun openIngredientQuantityPicker(item: Item) {
        val remembered = _uiState.value.lastLoggedAmounts[item.itemId]
        val quantity: Double?
        val servingSizeId: Int?
        if (remembered != null) {
            quantity = remembered.quantity
            servingSizeId = remembered.servingSizeId
        } else {
            quantity = item.lastLoggedQuantity?.toDoubleOrNull()
            servingSizeId = item.lastLoggedServingSizeId
        }
        _uiState.value = _uiState.value.copy(
            itemForIngredientPicker = item,
            ingredientQuantityInput = quantity?.let { formatQuantity(it) } ?: "100",
            ingredientServingSizeId = servingSizeId,
            editingIngredientItemId = null,
            addIngredientError = null
        )
    }

    /** Opened by tapping an ALREADY-added ingredient row (see design
     * discussion: "the ingredients list should be tappable... its item
     * info opens... we are able to adjust the quantity... or remove it
     * entirely") -- fetches the full Item (RecipeIngredient only
     * carries denormalized name/image/kcal, not the full per-100g
     * macros or serving_sizes list this picker needs, same reasoning as
     * openLogDetail's own item fetch), and pre-fills the EXISTING
     * quantity/serving rather than defaulting to 100g. */
    fun openIngredientEdit(ingredient: RecipeIngredient) {
        _uiState.value = _uiState.value.copy(addIngredientError = null)
        viewModelScope.launch {
            try {
                val item = ApiClient.service.getItem(ingredient.itemId)
                _uiState.value = _uiState.value.copy(
                    itemForIngredientPicker = item,
                    ingredientQuantityInput = ingredient.quantity,
                    ingredientServingSizeId = ingredient.servingSizeId,
                    editingIngredientItemId = ingredient.itemId
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(addIngredientError = e.message ?: "Couldn't load that item")
            }
        }
    }

    fun dismissIngredientQuantityPicker() {
        _uiState.value = _uiState.value.copy(
            itemForIngredientPicker = null,
            editingIngredientItemId = null,
            addIngredientError = null
        )
    }

    fun updateIngredientQuantityInput(value: String) {
        _uiState.value = _uiState.value.copy(ingredientQuantityInput = value)
    }

    /** Resets quantity to "1" on unit change -- same reasoning as
     * MealDetailViewModel's other quantity/serving pickers (see
     * updateLogServingSize's doc comment). */
    fun updateIngredientServingSize(servingSizeId: Int?) {
        _uiState.value = _uiState.value.copy(ingredientServingSizeId = servingSizeId, ingredientQuantityInput = "1")
    }

    /** POSTs a new ingredient, or PATCHes an existing one if
     * editingIngredientItemId is set (see that field's doc comment) --
     * same "one confirm function branches on whether we're editing"
     * pattern as confirmLogItemQuantity. */
    fun confirmAddIngredient() {
        val state = _uiState.value
        val recipe = state.recipeToView ?: return
        val item = state.itemForIngredientPicker ?: return
        val quantity = state.ingredientQuantityInput.toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _uiState.value = state.copy(addIngredientError = "Enter a valid quantity")
            return
        }

        _uiState.value = state.copy(isAddingIngredient = true, addIngredientError = null)
        viewModelScope.launch {
            try {
                val editingItemId = state.editingIngredientItemId
                val updated = if (editingItemId != null) {
                    ApiClient.service.updateRecipeIngredient(
                        recipe.recipeId,
                        editingItemId,
                        quantity = quantity,
                        servingSizeId = state.ingredientServingSizeId
                    )
                } else {
                    ApiClient.service.addRecipeIngredient(
                        recipe.recipeId,
                        RecipeIngredientCreateRequest(
                            itemId = item.itemId,
                            servingSizeId = state.ingredientServingSizeId,
                            quantity = quantity
                        )
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isAddingIngredient = false,
                    itemForIngredientPicker = null,
                    editingIngredientItemId = null,
                    recipeToView = updated
                )
                // Refreshes whatever's currently shown without clearing
                // the user's typed query - same "keep us where we are"
                // fix as MealDetailViewModel.refreshSearchAfterAdd.
                if (editingItemId == null) {
                    val query = _uiState.value.ingredientSearchQuery
                    if (query.isBlank()) loadRecentIngredientItems() else updateIngredientSearchQuery(query)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAddingIngredient = false,
                    addIngredientError = e.message ?: "Couldn't save that ingredient"
                )
            }
        }
    }

    /** "Remove" text inside the picker, shown only when editing an
     * existing ingredient (see ItemQuantityDialog's onRemove param) --
     * removes it entirely rather than saving a quantity change. */
    fun removeIngredientFromPicker() {
        val recipe = _uiState.value.recipeToView ?: return
        val itemId = _uiState.value.editingIngredientItemId ?: return
        _uiState.value = _uiState.value.copy(isAddingIngredient = true, addIngredientError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.removeRecipeIngredient(recipe.recipeId, itemId)
                _uiState.value = _uiState.value.copy(
                    isAddingIngredient = false,
                    itemForIngredientPicker = null,
                    editingIngredientItemId = null,
                    recipeToView = updated
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAddingIngredient = false,
                    addIngredientError = e.message ?: "Couldn't remove that ingredient"
                )
            }
        }
    }

    /** Swipe-left-to-delete on an ingredient row -- same "always snap
     * back, let actual removal from recipeToView drive whether the row
     * disappears" pattern as LogRow's swipe-to-delete (see that
     * composable's doc comment for the bug this avoids: a swipe box
     * that commits to its own internal "dismissed" state independently
     * of whether the underlying delete actually succeeded can get
     * visually stuck). No confirm dialog, same as LogRow. */
    fun removeIngredientBySwipe(ingredient: RecipeIngredient) {
        val recipe = _uiState.value.recipeToView ?: return
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.removeRecipeIngredient(recipe.recipeId, ingredient.itemId)
                _uiState.value = _uiState.value.copy(recipeToView = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    recipeDetailError = e.message ?: "Couldn't remove that ingredient"
                )
            }
        }
    }

    // --- Change recipe/meal image (tap the hero card) ---

    fun updateRecipeImage(imagePath: String) {
        val recipe = _uiState.value.recipeToView ?: return
        _uiState.value = _uiState.value.copy(isUploadingRecipeImage = true, recipeImageError = null)
        viewModelScope.launch {
            try {
                val updated = ApiClient.service.updateRecipe(
                    recipe.recipeId,
                    RecipeUpdateRequest(imagePath = imagePath)
                )
                _uiState.value = _uiState.value.copy(isUploadingRecipeImage = false, recipeToView = updated)
                refreshSearchAfterAdd()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingRecipeImage = false,
                    recipeImageError = e.message ?: "Couldn't update the photo"
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
        // Same "session memory first, then the item's own persisted
        // fields" fallback as openItemQuantityPicker -- this function
        // only has an id, not the full Item, so looks it up from
        // whichever list it's currently visible in (recent or search
        // results) to reach those persisted fields. See design
        // discussion: "if i logged 12g of something for lunch and then
        // go to log dinner, it's 100g again".
        val item = state.recentItems.find { it.itemId == itemId } ?: state.searchResults.find { it.itemId == itemId }
        val quantity: Double
        val servingSizeId: Int?
        if (remembered != null) {
            quantity = remembered.quantity
            servingSizeId = remembered.servingSizeId
        } else {
            quantity = item?.lastLoggedQuantity?.toDoubleOrNull() ?: QUICK_LOG_QUANTITY_G
            servingSizeId = item?.lastLoggedServingSizeId
        }

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

    /** Prefers, in order: this session's own lastLoggedAmounts map
     * (freshest, e.g. if this exact item was just logged moments ago
     * and the item object passed in hasn't been re-fetched since), then
     * the Item's own persisted last-logged fields (survives across
     * meals/days/app restarts - see design discussion: "if i logged
     * 12g of something for lunch and then go to log dinner, it's 100g
     * again", which was because lastLoggedAmounts alone never survived
     * crossing a meal boundary), then finally the flat 100g default. */
    fun openItemQuantityPicker(item: Item) {
        val remembered = _uiState.value.lastLoggedAmounts[item.itemId]
        val quantity: Double?
        val servingSizeId: Int?
        if (remembered != null) {
            quantity = remembered.quantity
            servingSizeId = remembered.servingSizeId
        } else {
            quantity = item.lastLoggedQuantity?.toDoubleOrNull()
            servingSizeId = item.lastLoggedServingSizeId
        }
        _uiState.value = _uiState.value.copy(
            itemToLog = item,
            logQuantityInput = quantity?.let { formatQuantity(it) } ?: formatQuantity(QUICK_LOG_QUANTITY_G),
            logServingSizeId = servingSizeId,
            logItemError = null
        )
    }

    fun dismissItemQuantityPicker() {
        _uiState.value = _uiState.value.copy(itemToLog = null, editingLogId = null, logItemError = null)
    }

    fun updateLogQuantityInput(value: String) {
        _uiState.value = _uiState.value.copy(logQuantityInput = value)
    }

    /** null = switch to raw grams. Resets quantity to "1" whenever the
     * unit changes -- otherwise whatever number was typed for the
     * PREVIOUS unit gets reinterpreted against the new one (e.g. "100"
     * grams becomes "100 slices" = 6200g for a 62g protein bar), which
     * is never what was intended. */
    fun updateLogServingSize(servingSizeId: Int?) {
        _uiState.value = _uiState.value.copy(logServingSizeId = servingSizeId, logQuantityInput = "1")
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
                // Refreshes the recent/search list's underlying Item
                // data too, not just "was something added" -- editing an
                // EXISTING log's quantity also updates that item's
                // persisted last-logged fields server-side (see
                // update_log on the backend), so the list needs
                // refreshing here too, not just for brand-new logs.
                // Previously skipped entirely when editingLogId was set,
                // which left the list showing a stale/default quantity
                // preview even after you'd just corrected it (see design
                // discussion: "the latest logged list still shows all
                // servings as 100g, even if that's not the saved value,
                // we're overriding the display to 100g somewhere" -- it
                // wasn't an override, it was this list never refreshing
                // after an edit).
                refreshSearchAfterAdd()
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
                    // Reset to 1, same reasoning as updateLogServingSize
                    // -- otherwise whatever gram quantity was typed
                    // before switching units (e.g. "100") gets
                    // reinterpreted as a multiplier of the NEW serving's
                    // weight (100 x a 62g protein bar = 6200g), which is
                    // never what was intended (see design discussion).
                    logQuantityInput = "1"
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
            editItemCountsAsAddedSugar = item.countsAsAddedSugar,
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
    /** Cycles null -> true -> false -> null (see
     * editItemCountsAsAddedSugar's doc comment for what each state
     * means) -- a plain Boolean toggle can't represent "use the
     * default heuristic" as a state, only on/off. */
    fun cycleEditItemCountsAsAddedSugar() {
        val next = when (_uiState.value.editItemCountsAsAddedSugar) {
            null -> true
            true -> false
            false -> null
        }
        _uiState.value = _uiState.value.copy(editItemCountsAsAddedSugar = next)
    }

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
                        sodiumMg100g = state.editItemSaltG.toDoubleOrNull()?.times(1000.0)?.div(SALT_TO_SODIUM_RATIO),
                        countsAsAddedSugar = state.editItemCountsAsAddedSugar
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
     * Recipe-based logs (log.itemId == null) open the SAME rich
     * RecipeInfoScreen used for browsing/logging a recipe from search
     * (see design discussion: "tapping a logged recipe opens the info
     * page but that should include the ingredient list as well and we
     * should be able to edit that recipe from there") - fetches the
     * full RecipeDetail and pre-fills recipeLogQuantityInput with THIS
     * log's quantity, with recipeLogInstanceId set so confirming
     * PATCHes that one log instead of creating a new one. A "meal" can
     * never reach this branch, since meal logs always expand into per-
     * ingredient item logs (recipe_id is never set for those) - only an
     * actual recipe_type="recipe" log references a recipe directly. */
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

        val recipeId = log.recipeId ?: return
        _uiState.value = _uiState.value.copy(
            isLoadingRecipeDetail = true,
            recipeDetailError = null,
            recipeLogQuantityInput = log.quantity.toDoubleOrNull()?.let { formatQuantity(it) } ?: log.quantity,
            recipeLogInstanceId = log.id
        )
        viewModelScope.launch {
            try {
                val detail = ApiClient.service.getRecipe(recipeId)
                _uiState.value = _uiState.value.copy(isLoadingRecipeDetail = false, recipeToView = detail)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecipeDetail = false,
                    recipeDetailError = e.message ?: "Couldn't load that recipe"
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
                if (state.recipeLogInstanceId == logId) {
                    _uiState.value = _uiState.value.copy(recipeToView = null, recipeLogInstanceId = null)
                }
                load(date, state.mealType)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(recipeDetailError = e.message ?: "Couldn't delete")
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
     * Passes each log's quantity/servingSizeId straight through --
     * recipe_ingredients can now natively store a serving the same way
     * logs do (see the "custom servings for recipe ingredients" schema
     * change), so there's no longer a need to fetch each item and
     * convert servings to grams here; the backend/display layer handles
     * that the same way it already does for logs.
     *
     * Recipe-based logs are still skipped, since recipe_ingredients can
     * only reference items, not other recipes.
     */
    fun saveAsMeal() {
        val state = _uiState.value
        val name = state.mealNameInput.trim()
        if (name.isEmpty()) return

        val ingredients = state.logs.mapNotNull { log ->
            val itemId = log.itemId ?: return@mapNotNull null
            val quantity = log.quantity.toDoubleOrNull() ?: return@mapNotNull null
            RecipeIngredientCreateRequest(itemId = itemId, servingSizeId = log.servingSizeId, quantity = quantity)
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