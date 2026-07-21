package com.mealtracker.android.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeDetail
import com.mealtracker.android.network.models.RecipeIngredient
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.health.HealthConnectPreferences
import com.mealtracker.android.ui.components.CatalogVisuals
import com.mealtracker.android.ui.components.CreateServingDialog
import com.mealtracker.android.ui.components.ItemQuantityDialog
import com.mealtracker.android.ui.components.ItemResultsList
import com.mealtracker.android.ui.components.CropDialog
import com.mealtracker.android.ui.components.decodeBitmapWithCorrectOrientation
import com.mealtracker.android.ui.components.MacroColors
import com.mealtracker.android.ui.components.MacroRingsRow
import com.mealtracker.android.ui.components.MealVisuals
import com.mealtracker.android.ui.theme.KcalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt
import java.time.LocalDate

private val WhiteCardColors @Composable get() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
private val SEARCH_BAR_SHAPE = RoundedCornerShape(24.dp)

/** Wraps either an Item or a Recipe so both can live in ONE list
 * together, sorted by shared last-logged recency (see design
 * discussion: "the idea was always to have one list where they're all
 * together... what did you think we had the filters for"). Only used
 * for the ALL filter's rendering -- Product/Ingredient/Recipe/Meal each
 * only ever show one type, so they don't need this. */
private sealed class CatalogEntry {
    abstract val lastLoggedAt: String?

    data class ItemEntry(val item: Item) : CatalogEntry() {
        override val lastLoggedAt: String? = item.lastLoggedAt
    }

    data class RecipeEntry(val recipe: Recipe) : CatalogEntry() {
        override val lastLoggedAt: String? = recipe.lastLoggedAt
    }
}

/** Merges items and recipes into ONE list ordered by shared
 * last_logged_at, descending (most recent first) -- both types already
 * come back individually sorted this way from their own endpoints (see
 * items.py/recipes.py's own list_ orderings), but merging two
 * separately-sorted lists still needs the actual timestamp to compare
 * across types, which is why Item/Recipe.lastLoggedAt exists on the
 * client at all now (previously only used server-side). Entries with no
 * last_logged_at (never logged) sort last, same "nullslast" convention
 * the backend already uses. */
private fun mergeByLastLogged(items: List<Item>, recipes: List<Recipe>): List<CatalogEntry> {
    val combined: List<CatalogEntry> = items.map { CatalogEntry.ItemEntry(it) } +
        recipes.map { CatalogEntry.RecipeEntry(it) }
    return combined.sortedByDescending { entry ->
        entry.lastLoggedAt?.let { raw -> runCatching { java.time.Instant.parse(raw) }.getOrNull() }
            ?: java.time.Instant.EPOCH
    }
}

/**
 * Opens for ANY meal card tap, even an empty one (per design doc).
 *
 *   - DEFAULT (sheet collapsed): plain white background, no colored
 *     hero -- per design discussion, the pastel treatment was dropped
 *     from the main screen entirely. Back button, meal name/star, a
 *     kcal+macro white Card, and a logged-items white Card, same as
 *     before, just no colored wrapper behind them anymore.
 *   - EXPANDED (sheet dragged/tapped open): the header REARRANGES into
 *     a compact version -- meal name + a minimalist kcal bar, laid out
 *     side by side -- and THIS compact header is the only place the
 *     pastel hero color (MealVisuals, status-bar-tinted) still appears.
 *     Everything else scrolls out of view under the now-mostly-full-
 *     screen sheet. Driven off the sheet's targetValue (see below) and
 *     wrapped in a Crossfade, so it fades between the two header states
 *     as you drag rather than snapping instantly.
 *   - the draggable BottomSheetScaffold sheet is the ADD-ITEM picker,
 *     replacing the app's normal nav bar on this screen (see
 *     AppNavHost's routesWithoutBottomBar). Two equal-weight method
 *     icons: Search (merged with what used to be a separate "Saved"
 *     tab -- both showed a search bar + results list, the only
 *     difference was what populated it before typing, so there's no
 *     reason for them to be different screens) and Barcode. Tapping
 *     either now also programmatically expands the sheet. Search bar
 *     is a pill shape (SEARCH_BAR_SHAPE). Barcode embeds the FULL
 *     AddItemScreen flow directly (camera -> match/no-match -> product
 *     photo -> crop -> label -> form -> save) -- NOT a navigation to a
 *     separate screen, per design discussion ("we want it inside the
 *     card"). AddItemViewModel.attachToMeal() tells that flow which
 *     meal to actually log the result to once it's done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailScreen(
    date: LocalDate,
    mealType: String,
    viewModel: MealDetailViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val heroColor = MealVisuals.backgroundFor(mealType)
    val healthConnectContext = androidx.compose.ui.platform.LocalContext.current

    // Runs on ANY change to this meal's logs -- add, edit, or delete all
    // naturally show up as a change to state.logs, so this is simpler
    // and more robust than hooking into every individual mutation
    // function (logItemQuickly, confirmLogItemQuantity, confirmRecipeLog,
    // deleteLog, ...) separately. Per design discussion: sync unit is
    // the MEAL (matching this app's own logging unit and Health
    // Connect's own per-meal mealType field), not individual items --
    // see HealthConnectManager.writeMealNutrition's doc comment for how
    // the upsert-on-edit behavior works via clientRecordId/
    // clientRecordVersion, with no Health Connect ID stored on our side
    // at all.
    //
    // NOTE: previously sugar/saturated fat/sodium were left null here
    // since Log didn't expose them at all -- now that the backend
    // returns them (see design discussion), this syncs the full set
    // NutritionRecord supports, not a partial picture.
    LaunchedEffect(state.logs, state.isLoading, date, mealType) {
        // Critical guard, not an optimization: state.logs defaults to
        // emptyList() and isLoading defaults to true BEFORE the real
        // network response arrives (see MealDetailUiState's defaults).
        // Without this check, this effect fired on every single screen
        // open with that placeholder empty list, indistinguishable from
        // "this meal genuinely has nothing logged" -- which called
        // deleteMealNutrition and wiped whatever was already synced for
        // that day/meal, before the follow-up effect (once real data
        // loaded) had a chance to write it back. If the screen was
        // closed/navigated away before that second effect completed,
        // the deletion was the only thing that actually took effect,
        // permanently (see design discussion: "i'm not sure what
        // triggers the push... and the one log we did have there is
        // gone" -- this was why).
        if (state.isLoading) return@LaunchedEffect
        if (!HealthConnectPreferences.isNutritionExportEnabled(healthConnectContext)) return@LaunchedEffect
        if (!HealthConnectManager.isAvailable(healthConnectContext)) return@LaunchedEffect
        if (!HealthConnectManager.hasNutritionPermission(healthConnectContext)) return@LaunchedEffect

        try {
            if (state.logs.isEmpty()) {
                HealthConnectManager.deleteMealNutrition(healthConnectContext, date, mealType)
            } else {
                val totals = HealthConnectManager.MealNutritionTotals(
                    kcal = state.logs.sumOf { it.kcalLogged }.toDouble(),
                    proteinG = state.logs.sumOf { it.proteinGLogged }.toDouble(),
                    carbsG = state.logs.sumOf { it.carbsGLogged }.toDouble(),
                    fatG = state.logs.sumOf { it.fatGLogged }.toDouble(),
                    saturatedFatG = state.logs.sumOf { it.saturatedFatGLogged }.toDouble(),
                    sugarG = state.logs.sumOf { it.sugarGLogged }.toDouble(),
                    fiberG = state.logs.sumOf { it.fiberGLogged }.toDouble(),
                    sodiumMg = state.logs.sumOf { it.sodiumMgLogged }.toDouble()
                )
                HealthConnectManager.writeMealNutrition(healthConnectContext, date, mealType, totals)
            }
        } catch (e: Exception) {
            // Best-effort, same reasoning as every other Health Connect
            // call in this app -- a sync hiccup shouldn't disrupt using
            // the rest of the app, and there's no user-facing action to
            // take on a background sync failure anyway.
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState()
    val coroutineScope = rememberCoroutineScope()
    // targetValue (not currentValue) updates as soon as the drag gesture
    // is predicted to settle past the halfway point -- i.e. partway
    // through the drag, before it's released/settled -- which is what
    // lets the Crossfade below actually respond WHILE dragging rather
    // than only snapping once the drag fully finishes.
    val isSheetExpanded = scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded

    // BottomSheetScaffold normally sizes "Expanded" to the sheet
    // CONTENT's own height -- with sparse content (a couple of icons +
    // a short list) that meant Expanded barely moved past the peek
    // height (see design discussion: "doesn't go higher than ~25%").
    // Forcing a minimum height here makes Expanded a fixed, predictable
    // amount of the screen regardless of how much is actually in it.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val expandedSheetMinHeight = screenHeightDp * 0.82f

    fun selectMethod(mode: AddItemSheetMode) {
        viewModel.setSheetMode(mode)
        coroutineScope.launch { scaffoldState.bottomSheetState.expand() }
    }

    LaunchedEffect(date, mealType) {
        viewModel.load(date, mealType)
    }

    // Wraps the whole screen so ItemLogPageDialog (moved to the end,
    // after BottomSheetScaffold) can render as a LATER sibling and
    // therefore draw on top of everything else -- see that block's own
    // comment near the end of this function for why it needs to be a
    // Box sibling here rather than its own separate Dialog window.
    Box(modifier = Modifier.fillMaxSize()) {

    if (state.showSaveMealDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveMealDialog() },
            title = { Text("Save this meal") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.mealNameInput,
                        onValueChange = { viewModel.updateMealNameInput(it) },
                        label = { Text("Meal name") }
                    )
                    if (state.saveMealError != null) {
                        Text(
                            state.saveMealError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveAsMeal() },
                    enabled = state.mealNameInput.isNotBlank() && !state.isSavingMeal
                ) {
                    Text(if (state.isSavingMeal) "Saving..." else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSaveMealDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // Was 88.dp -- the icon+label content was taller than that once
        // padding was accounted for, clipping the labels at the bottom
        // of the peeked sheet (see design discussion).
        sheetPeekHeight = 110.dp,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = expandedSheetMinHeight)
                    .padding(top = 4.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AddMethodIcon(
                        Icons.Filled.Search, "Search",
                        selected = state.sheetMode == AddItemSheetMode.SEARCH || state.enteredAddFlowViaUsdaLink,
                        onClick = { selectMethod(AddItemSheetMode.SEARCH) }
                    )
                    AddMethodIcon(
                        Icons.Filled.QrCodeScanner, "Barcode",
                        selected = state.sheetMode == AddItemSheetMode.BARCODE && !state.enteredAddFlowViaUsdaLink,
                        onClick = { selectMethod(AddItemSheetMode.BARCODE) }
                    )
                    AddMethodIcon(
                        Icons.Filled.Add, "Create",
                        selected = state.sheetMode == AddItemSheetMode.CREATE,
                        onClick = { selectMethod(AddItemSheetMode.CREATE) }
                    )
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

                // Same K2 compiler workaround as AddItemScreen.kt's
                // when(state.phase) -- "PostponedLambdaExitNode not
                // initialized" is a known K2 frontend bug triggered by
                // large when expressions containing nested lambdas,
                // especially (as here) when the whole thing sits inside
                // ANOTHER lambda argument (sheetContent's lambda, itself
                // passed to BottomSheetScaffold). Isolating it in its
                // own local function body works around it without
                // changing any logic -- a local function still captures
                // everything from the enclosing scope (state, viewModel,
                // selectMethod, coroutineScope, scaffoldState, date,
                // mealType, etc.) by closure exactly as before.
                @Composable
                fun SheetModeContent() {
                    when (state.sheetMode) {
                    AddItemSheetMode.SEARCH -> {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            label = { Text("Search for a food") },
                            shape = SEARCH_BAR_SHAPE,
                            singleLine = true,
                            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                }
                            } else null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SearchFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = state.searchFilter == filter,
                                    onClick = { viewModel.updateSearchFilter(filter) },
                                    label = { Text(filter.label) },
                                    // Without this, the selected state
                                    // falls back to Material3's own
                                    // unset default (secondaryContainer),
                                    // which is a baseline purple neither
                                    // color scheme ever sets explicitly -
                                    // same root cause as the bottom nav
                                    // and profile screen's period chips
                                    // (see design discussion: "the
                                    // accents in the search filters are
                                    // still purple").
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                        // Defaults to recently added/updated catalog
                        // items when the query is blank -- this IS the
                        // old "Saved" tab, now just the empty-query state
                        // of Search (see loadRecentItems' doc comment for
                        // why "recent" changed meaning from "logged" to
                        // "added").
                        val showingRecent = state.searchQuery.isBlank()
                        if (state.searchFilter == SearchFilter.RECIPE || state.searchFilter == SearchFilter.MEAL) {
                            RecipeResultsList(
                                recipes = if (showingRecent) state.recentRecipes else state.recipeSearchResults,
                                isLoading = if (showingRecent) false else state.isSearchingRecipes,
                                emptyMessage = if (showingRecent) "No recipes yet" else "No matches",
                                quickLoggingRecipeId = state.quickLoggingRecipeId,
                                onRecipeClick = { recipe -> viewModel.openRecipeDetail(recipe.recipeId) },
                                onQuickAddClick = { recipe -> viewModel.logRecipeQuickly(recipe) }
                            )
                        } else if (state.searchFilter == SearchFilter.ALL) {
                            // True interleaving, not stacked sections -
                            // items and recipes/meals sorted together by
                            // shared last-logged recency (see design
                            // discussion: "the idea was always to have
                            // one list where they're all together...
                            // what did you think we had the filters
                            // for"). Reuses ItemResultsList/
                            // RecipeResultsList's own row rendering by
                            // calling each with a SINGLE-element list in
                            // the right order, rather than duplicating
                            // their row UI in a third composable -
                            // scrollable=false on both (see those
                            // composables' own doc comments) so this
                            // reads as one continuous list under the
                            // outer sheet's own scroll, not N separate
                            // tiny scrollable boxes.
                            val allItems = if (showingRecent) state.recentItems else state.searchResults
                            val allRecipes = if (showingRecent) state.recentRecipes else state.recipeSearchResults
                            val isLoadingAll = if (showingRecent) {
                                state.isLoadingRecentItems
                            } else {
                                state.isSearching || state.isSearchingRecipes
                            }
                            state.allFilterRecipeError ?.let { error ->
                                Text(
                                    state.allFilterRecipeError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            when {
                                isLoadingAll -> {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.material3.CircularProgressIndicator()
                                    }
                                }
                                allItems.isEmpty() && allRecipes.isEmpty() -> {
                                    Text(
                                        if (showingRecent) "Nothing yet" else "No matches",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                                else -> {
                                    mergeByLastLogged(allItems, allRecipes).forEach { entry ->
                                        when (entry) {
                                            is CatalogEntry.ItemEntry -> ItemResultsList(
                                                items = listOf(entry.item),
                                                isLoading = false,
                                                emptyMessage = "",
                                                quickLoggingItemId = state.quickLoggingItemId,
                                                lastLoggedAmounts = state.lastLoggedAmounts,
                                                onItemClick = { item -> viewModel.openItemQuantityPicker(item) },
                                                onQuickAddClick = { itemId -> viewModel.logItemQuickly(itemId) },
                                                scrollable = false
                                            )
                                            is CatalogEntry.RecipeEntry -> RecipeResultsList(
                                                recipes = listOf(entry.recipe),
                                                isLoading = false,
                                                emptyMessage = "",
                                                quickLoggingRecipeId = state.quickLoggingRecipeId,
                                                onRecipeClick = { recipe -> viewModel.openRecipeDetail(recipe.recipeId) },
                                                onQuickAddClick = { recipe -> viewModel.logRecipeQuickly(recipe) },
                                                scrollable = false
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            ItemResultsList(
                                items = if (showingRecent) state.recentItems else state.searchResults,
                                isLoading = if (showingRecent) state.isLoadingRecentItems else state.isSearching,
                                emptyMessage = if (showingRecent) "No items yet" else "No matches",
                                quickLoggingItemId = state.quickLoggingItemId,
                                lastLoggedAmounts = state.lastLoggedAmounts,
                                onItemClick = { item -> viewModel.openItemQuantityPicker(item) },
                                onQuickAddClick = { itemId -> viewModel.logItemQuickly(itemId) },
                                // PRODUCT/INGREDIENT have nothing else
                                // stacked with them, so keep the normal
                                // bounded/scrollable box.
                                scrollable = true
                            )
                        }
                        // USDA lookup lives ONLY here now, not in the
                        // barcode flow (see design discussion) -- for
                        // raw/whole ingredients our own catalog and
                        // barcodes won't have.
                        Text(
                            "Can't find it? Search USDA for a raw ingredient",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clickable { viewModel.requestUsdaSearch() }
                        )
                    }
                    }
                    AddItemSheetMode.BARCODE -> {
                        // Scoped by date+mealType so switching meals (or
                        // re-entering after a save) gets a fresh flow
                        // rather than resuming mid-scan from a different
                        // meal's attempt.
                        val addItemViewModel: AddItemViewModel =
                            viewModel(key = "add_item_${date}_$mealType")
                        LaunchedEffect(date, mealType) {
                            addItemViewModel.attachToMeal(date, mealType)
                        }
                        LaunchedEffect(state.requestedUsdaSearchQuery) {
                            val query = state.requestedUsdaSearchQuery
                            if (query != null) {
                                addItemViewModel.jumpToUsdaSearch(query)
                                viewModel.clearUsdaSearchRequest()
                            }
                        }
                        AddItemScreen(
                            viewModel = addItemViewModel,
                            onBack = {
                                // Drop the in-progress flow entirely --
                                // previously only onDone reset it, so
                                // backing out mid-scan/mid-form and then
                                // tapping Barcode again would resume
                                // wherever you left off instead of
                                // starting fresh.
                                addItemViewModel.resetToScanChoice()
                                viewModel.setSheetMode(AddItemSheetMode.SEARCH)
                                coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() }
                            },
                            onDone = {
                                addItemViewModel.resetToScanChoice()
                                viewModel.setSheetMode(AddItemSheetMode.SEARCH)
                                viewModel.load(date, mealType)
                                viewModel.refreshSearchAfterAdd()
                                coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() }
                            },
                            onOpenItemDetail = { item ->
                                addItemViewModel.resetToScanChoice()
                                viewModel.setSheetMode(AddItemSheetMode.SEARCH)
                                viewModel.openItemQuantityPicker(item)
                            }
                        )
                    }
                    // Renders full-screen instead, as a later Box
                    // sibling outside BottomSheetScaffold entirely (see
                    // the createRecipeViewModel block near
                    // ItemLogPageDialog/RecipeInfoScreen's own mounting
                    // point) -- this used to render right here, confined
                    // to the sheet's own height, which meant the
                    // keyboard ate most of the available space the
                    // moment any text field inside it (search, barcode
                    // entry, etc) got focus (see design discussion:
                    // "the keyboard obscures most of it").
                    AddItemSheetMode.CREATE -> {}
                }
                }
                SheetModeContent()

                if (state.quickLogError != null) {
                    Text(
                        state.quickLogError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        // meal_detail is in AppNavHost's edgeToEdgeStatusBarRoutes, so
        // unlike most screens it does NOT get an automatic status-bar
        // inset here -- CompactMealHeader (below) is the one place that
        // deliberately bleeds color up behind the status bar; everywhere
        // else in this screen still needs its own statusBarsPadding so
        // content doesn't render under the system clock/icons.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .let { if (isSheetExpanded) it else it.statusBarsPadding() }
        ) {
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't load this meal", style = MaterialTheme.typography.titleMedium)
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Crossfade(
                    targetState = isSheetExpanded,
                    animationSpec = tween(220),
                    label = "meal_header_crossfade"
                ) { expanded ->
                    if (expanded) {
                        CompactMealHeader(
                            displayName = state.displayName,
                            eatenKcal = state.eatenKcal,
                            goalKcal = state.goalKcal,
                            heroColor = heroColor
                        )
                    } else {
                        DefaultMealContent(
                            state = state,
                            onBack = onBack,
                            onOpenSaveMealDialog = { viewModel.openSaveMealDialog() },
                            onLogClick = { log -> viewModel.openLogDetail(log) },
                            onLogDelete = { logId -> viewModel.deleteLog(logId) }
                        )
                    }
                }
            }
        }
    }

    // Moved here (was a top-level composable call near the top of this
    // function) so it renders as a LATER sibling of BottomSheetScaffold
    // above -- see ItemLogPageDialog's own doc comment for why it needs
    // to be a Box sibling in the caller's own window now, instead of
    // wrapping itself in a separate Dialog (which had the same
    // unreliable full-screen sizing issue CropDialog itself used to
    // have, before being fixed the same way).
    // Same "later Box sibling, not a Dialog()" reasoning as
    // ItemLogPageDialog just below -- see that composable's own doc
    // comment for the full history of why.
    // Full-screen, not confined to the bottom sheet -- see the empty
    // AddItemSheetMode.CREATE branch inside sheetContent for why (the
    // keyboard ate most of the sheet's available height whenever any
    // text field in this flow, including the embedded barcode/search
    // screen, got focus).
    if (state.sheetMode == AddItemSheetMode.CREATE) {
        // Same per-meal scoping reasoning as AddItemViewModel elsewhere
        // in this screen -- a fresh build each time this meal's Create
        // tab is re-entered, rather than resuming a half-finished
        // recipe from switching meals and back.
        val createRecipeViewModel: CreateRecipeViewModel =
            viewModel(key = "create_recipe_${date}_$mealType")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            CreateRecipeContent(
                viewModel = createRecipeViewModel,
                lastLoggedAmounts = state.lastLoggedAmounts,
                onLogToMeal = { recipe ->
                    viewModel.logRecipeQuickly(recipe)
                    createRecipeViewModel.reset()
                    viewModel.setSheetMode(AddItemSheetMode.SEARCH)
                    coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() }
                },
                onDone = {
                    createRecipeViewModel.reset()
                    viewModel.setSheetMode(AddItemSheetMode.SEARCH)
                    viewModel.refreshSearchAfterAdd()
                    coroutineScope.launch { scaffoldState.bottomSheetState.partialExpand() }
                }
            )
        }
    }

    val recipeToView = state.recipeToView
    if (recipeToView != null) {
        RecipeInfoScreen(
            recipe = recipeToView,
            quantityInput = state.recipeLogQuantityInput,
            isLogging = state.isLoggingRecipeDetail,
            error = state.recipeDetailError,
            onQuantityChange = { viewModel.updateRecipeLogQuantityInput(it) },
            onConfirm = { viewModel.confirmRecipeLog() },
            onDismiss = { viewModel.dismissRecipeDetail() },
            goalFat = state.goalFat,
            goalProtein = state.goalProtein,
            goalCarbs = state.goalCarbs,
            goalFiber = state.goalFiber,
            logInstanceId = state.recipeLogInstanceId,
            onDeleteInstance = { viewModel.deleteRecipeLogInstance() },
            isEditing = state.isEditingRecipe,
            editName = state.editRecipeName,
            editServings = state.editRecipeServings,
            isSavingEdit = state.isSavingRecipeEdit,
            editError = state.recipeEditError,
            onEditClick = { viewModel.openRecipeEdit() },
            onEditNameChange = { viewModel.updateEditRecipeName(it) },
            onEditServingsChange = { viewModel.updateEditRecipeServings(it) },
            onSaveEdit = { viewModel.saveRecipeEdit() },
            onCancelEdit = { viewModel.dismissRecipeEdit() },
            showDeleteConfirm = state.showDeleteRecipeConfirm,
            isDeleting = state.isDeletingRecipe,
            onRequestDelete = { viewModel.requestDeleteRecipe() },
            onConfirmDelete = { viewModel.confirmDeleteRecipe() },
            onDismissDeleteConfirm = { viewModel.dismissDeleteRecipeConfirm() },
            ingredientSearchQuery = state.ingredientSearchQuery,
            ingredientSearchResults = state.ingredientSearchResults,
            isSearchingIngredients = state.isSearchingIngredients,
            recentIngredientItems = state.recentIngredientItems,
            isLoadingRecentIngredientItems = state.isLoadingRecentIngredientItems,
            onIngredientSearchQueryChange = { viewModel.updateIngredientSearchQuery(it) },
            itemForIngredientPicker = state.itemForIngredientPicker,
            ingredientQuantityInput = state.ingredientQuantityInput,
            ingredientServingSizeId = state.ingredientServingSizeId,
            editingIngredientItemId = state.editingIngredientItemId,
            isAddingIngredient = state.isAddingIngredient,
            addIngredientError = state.addIngredientError,
            onOpenIngredientPicker = { viewModel.openIngredientQuantityPicker(it) },
            onOpenIngredientEdit = { viewModel.openIngredientEdit(it) },
            onDismissIngredientPicker = { viewModel.dismissIngredientQuantityPicker() },
            onIngredientQuantityChange = { viewModel.updateIngredientQuantityInput(it) },
            onIngredientServingChange = { viewModel.updateIngredientServingSize(it) },
            onConfirmAddIngredient = { viewModel.confirmAddIngredient() },
            onRemoveFromPicker = { viewModel.removeIngredientFromPicker() },
            onRemoveBySwipe = { viewModel.removeIngredientBySwipe(it) },
            isUploadingImage = state.isUploadingRecipeImage,
            imageError = state.recipeImageError,
            onImageChanged = { viewModel.updateRecipeImage(it) }
        )
    } else if (state.isLoadingRecipeDetail) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            IconButton(onClick = { viewModel.dismissRecipeDetail() }, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Close")
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    val itemToLog = state.itemToLog
    if (itemToLog != null) {
        ItemLogPageDialog(
            item = itemToLog,
            goalFat = state.goalFat,
            goalProtein = state.goalProtein,
            goalCarbs = state.goalCarbs,
            goalFiber = state.goalFiber,
            quantityInput = state.logQuantityInput,
            servingSizeId = state.logServingSizeId,
            isLogging = state.isLoggingItem,
            error = state.logItemError,
            isEditing = state.editingLogId != null,
            onQuantityChange = { viewModel.updateLogQuantityInput(it) },
            onServingChange = { viewModel.updateLogServingSize(it) },
            onCreateNewServing = { viewModel.openCreateServingDialog() },
            onConfirm = { viewModel.confirmLogItemQuantity() },
            onDelete = {
                state.editingLogId?.let { viewModel.deleteLog(it) }
                viewModel.dismissItemQuantityPicker()
            },
            onDismiss = { viewModel.dismissItemQuantityPicker() },
            onImageUpdated = { updated -> viewModel.onItemImageUpdated(updated) },
            onEditClick = { viewModel.openEditItemDialog() }
        )

        if (state.showCreateServingDialog) {
            CreateServingDialog(
                name = state.newServingName,
                weightG = state.newServingWeightG,
                isCreating = state.isCreatingServing,
                error = state.createServingError,
                onNameChange = { viewModel.updateNewServingName(it) },
                onWeightChange = { viewModel.updateNewServingWeight(it) },
                onConfirm = { viewModel.createNewServing() },
                onDismiss = { viewModel.dismissCreateServingDialog() }
            )
        }

        if (state.showEditItemDialog) {
            EditItemDialog(
                name = state.editItemName,
                kcal = state.editItemKcal,
                protein = state.editItemProtein,
                carbs = state.editItemCarbs,
                fat = state.editItemFat,
                fiber = state.editItemFiber,
                sugar = state.editItemSugar,
                saturatedFat = state.editItemSaturatedFat,
                saltG = state.editItemSaltG,
                countsAsAddedSugar = state.editItemCountsAsAddedSugar,
                isSaving = state.isSavingItemEdit,
                error = state.editItemError,
                onNameChange = { viewModel.updateEditItemName(it) },
                onKcalChange = { viewModel.updateEditItemKcal(it) },
                onProteinChange = { viewModel.updateEditItemProtein(it) },
                onCarbsChange = { viewModel.updateEditItemCarbs(it) },
                onFatChange = { viewModel.updateEditItemFat(it) },
                onFiberChange = { viewModel.updateEditItemFiber(it) },
                onSugarChange = { viewModel.updateEditItemSugar(it) },
                onSaturatedFatChange = { viewModel.updateEditItemSaturatedFat(it) },
                onSaltChange = { viewModel.updateEditItemSalt(it) },
                onCyclesCountsAsAddedSugar = { viewModel.cycleEditItemCountsAsAddedSugar() },
                onConfirm = { viewModel.saveItemEdit() },
                onDismiss = { viewModel.dismissEditItemDialog() }
            )
        }
    }
    }
}

/** The full default-state header + kcal/macro card + logged-items card
 * -- extracted out so it can be one branch of the Crossfade in
 * MealDetailScreen, the other branch being CompactMealHeader. */
@Composable
private fun DefaultMealContent(
    state: MealDetailUiState,
    onBack: () -> Unit,
    onOpenSaveMealDialog: () -> Unit,
    onLogClick: (Log) -> Unit,
    onLogDelete: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        // Meal name, centered, with the star/bookmark icon right next
        // to it -- plain (no hero color) in this default state.
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(state.displayName, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onOpenSaveMealDialog) {
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = "Save as a meal")
                }
            }
        }
        if (state.saveMealSuccess) {
            Text(
                "\u2705 Saved as a reusable meal",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

        val kcalFraction = if (state.goalKcal > 0) {
            (state.eatenKcal.toFloat() / state.goalKcal.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Card(
            colors = WhiteCardColors,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "${state.eatenKcal} / ${state.goalKcal} Cal",
                    style = MaterialTheme.typography.titleMedium
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                LinearProgressIndicator(
                    progress = { kcalFraction },
                    color = KcalGreen,
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

                MacroRingsRow(
                    fatEaten = state.eatenFat, fatGoal = state.goalFat,
                    proteinEaten = state.eatenProtein, proteinGoal = state.goalProtein,
                    carbsEaten = state.eatenCarbs, carbsGoal = state.goalCarbs,
                    fiberEaten = state.eatenFiber, fiberGoal = state.goalFiber,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(
            colors = WhiteCardColors,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Logged items", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                if (state.logs.isEmpty()) {
                    Text(
                        "Nothing logged yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.logs.forEach { log ->
                        LogRow(
                            log = log,
                            onClick = { onLogClick(log) },
                            onDelete = { onLogDelete(log.id) }
                        )
                    }
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** Shown instead of the full header while the sheet is dragged/tapped
 * open -- meal name off to the side + a thin, unlabeled progress bar
 * next to it (not stacked below, per design discussion). This is the
 * ONLY place the meal-type hero color still appears in the default
 * flow (see design discussion: dropped from the main screen). */
@Composable
private fun CompactMealHeader(displayName: String, eatenKcal: Int, goalKcal: Int, heroColor: Color) {
    val kcalFraction = if (goalKcal > 0) (eatenKcal.toFloat() / goalKcal.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().background(heroColor)) {
        // Background bleeds to y=0; this pushes the readable content
        // down below the status bar icons without clipping the color
        // itself -- same approach as JournalScreen's hero.
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(displayName, style = MaterialTheme.typography.headlineSmall)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(kcalFraction)
                        .height(6.dp)
                        .background(KcalGreen, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

/** Small, subtle, equal-weight icon for each add-item method -- neither
 * should read as THE prominent action, they're just different doors
 * into the same sheet. Slightly tinted when it's the active mode. */
@Composable
private fun AddMethodIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // Was primaryContainer (a light teal tint) when selected -- too
    // close to the unselected surfaceVariant color to tell apart at a
    // glance, especially in dark mode. Using the actual primary teal
    // now, with onPrimary (light) icon tint for contrast against it --
    // see design discussion ("darker green color when selected, but
    // still vibrant enough to tell apart from the background").
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(background, CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = iconTint)
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}


/** Recipe/Meal filter's results list -- separate from ItemResultsList
 * since Recipes have a totally different shape (servings + totals_per_
 * serving, no per-100g macros or ServingSizes), and quick-log always
 * uses a flat 1-serving default (see logRecipeQuickly's doc comment)
 * rather than the item-quantity-picker page -- recipes don't have one
 * yet. */
@Composable
private fun RecipeResultsList(
    recipes: List<Recipe>,
    isLoading: Boolean,
    emptyMessage: String,
    quickLoggingRecipeId: Int?,
    onRecipeClick: (Recipe) -> Unit,
    onQuickAddClick: (Recipe) -> Unit,
    // false when stacked above ItemResultsList in the ALL-filter case
    // (see that call site) -- two independently height-bounded,
    // independently-scrollable regions stacked in the same outer
    // scrollable sheet creates nested scrolling, where this box's OWN
    // scroll has to be exhausted before the items below it can move at
    // all, which reads as "recipes/meals just stay on top" while
    // scrolling (see design discussion). Standalone usage (the actual
    // Recipe/Meal tabs, where this is the ONLY content in the sheet)
    // keeps the bounded/scrollable box as before.
    scrollable: Boolean = true
) {
    Column(
        modifier = if (scrollable) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        when {
            // Same fix as ItemResultsList -- don't blank already-shown
            // recipes to a spinner during a background refresh.
            isLoading && recipes.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            recipes.isEmpty() -> {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> {
                recipes.forEach { recipe ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = quickLoggingRecipeId == null) { onRecipeClick(recipe) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CatalogVisuals.backgroundFor(recipe.recipeType)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (recipe.imagePath != null) {
                                coil3.compose.AsyncImage(
                                    model = com.mealtracker.android.BuildConfig.BASE_URL + recipe.imagePath,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    CatalogVisuals.iconFor(recipe.recipeType),
                                    contentDescription = null,
                                    tint = CatalogVisuals.iconTint(),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
                            if (recipe.totalsPerServing != null) {
                                Text(
                                    "${recipe.totalsPerServing.kcal} Cal / serving",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (quickLoggingRecipeId == recipe.recipeId) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { onQuickAddClick(recipe) }) {
                                Icon(Icons.Filled.Add, contentDescription = "Quick add")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Recipe/meal info screen -- opened by tapping a recipe/meal row (see
 * design discussion: this used to not exist at all, tapping just
 * silently quick-logged the same as the "+" button, unlike items which
 * have ItemLogPageDialog for this). Shows what's actually in it
 * (ingredients, per-serving totals) before logging, and now also
 * supports editing (name/servings), deleting the recipe entirely,
 * adding/removing ingredients, and changing its photo -- see design
 * discussion: "we should also be able to edit existing recipes and
 * delete them".
 *
 * Same hero-image/scrollable-card treatment as ItemLogPageDialog below,
 * including the same "plain composable, later Box sibling, no Dialog()
 * wrapper" reasoning (see that composable's own doc comment for the
 * full history). Tapping the hero (not long-press, unlike
 * ItemLogPageDialog) opens the same camera/gallery/crop/upload pipeline,
 * just targeting the recipe's own image_path via PATCH /recipes/{id}
 * instead of an item's.
 *
 * Ingredient rows use the exact same image/name/quantity-or-serving/kcal
 * layout as LogRow in the Journal, per design discussion ("i'd like the
 * ingredient list to be the same as the one in the journal") -- see
 * IngredientRow below.
 *
 * "recipe" type gets an editable quantity (servings) field for LOGGING
 * (not editing), since LoggableEntryBase's quantity semantics for a
 * recipe log are "number of servings consumed", and shows its total
 * servings count. "meal" type has neither -- see
 * MealDetailUiState.recipeLogQuantityInput's doc comment for why (a meal
 * logs its originally-captured per-ingredient amounts as-is, and is
 * always exactly one serving, so showing/editing a servings count for
 * it would be showing a number that's never actually meaningful).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeInfoScreen(
    recipe: RecipeDetail,
    quantityInput: String,
    isLogging: Boolean,
    error: String?,
    onQuantityChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // Same per-meal goals ItemLogPageDialog uses, for the same colored
    // progress-bar treatment (see design discussion: "i wanted recipes
    // and meals to show macro info the same way items do").
    goalFat: Int,
    goalProtein: Int,
    goalCarbs: Int,
    goalFiber: Int,
    // Non-null = viewing this from an ALREADY-LOGGED instance (tapped a
    // logged recipe row) -- see MealDetailUiState.recipeLogInstanceId's
    // doc comment. Changes the bottom action area to "Save"/"Delete
    // this log" (editing/removing THAT INSTANCE) instead of "Log this
    // recipe" (creating a new one).
    logInstanceId: Int?,
    onDeleteInstance: () -> Unit,
    isEditing: Boolean,
    editName: String,
    editServings: String,
    isSavingEdit: Boolean,
    editError: String?,
    onEditClick: () -> Unit,
    onEditNameChange: (String) -> Unit,
    onEditServingsChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    showDeleteConfirm: Boolean,
    isDeleting: Boolean,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDeleteConfirm: () -> Unit,
    ingredientSearchQuery: String,
    ingredientSearchResults: List<Item>,
    isSearchingIngredients: Boolean,
    recentIngredientItems: List<Item>,
    isLoadingRecentIngredientItems: Boolean,
    onIngredientSearchQueryChange: (String) -> Unit,
    itemForIngredientPicker: Item?,
    ingredientQuantityInput: String,
    ingredientServingSizeId: Int?,
    editingIngredientItemId: Int?,
    isAddingIngredient: Boolean,
    addIngredientError: String?,
    onOpenIngredientPicker: (Item) -> Unit,
    onOpenIngredientEdit: (RecipeIngredient) -> Unit,
    onDismissIngredientPicker: () -> Unit,
    onIngredientQuantityChange: (String) -> Unit,
    onIngredientServingChange: (Int?) -> Unit,
    onConfirmAddIngredient: () -> Unit,
    onRemoveFromPicker: () -> Unit,
    onRemoveBySwipe: (RecipeIngredient) -> Unit,
    isUploadingImage: Boolean,
    imageError: String?,
    onImageChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Same tap-to-change-photo pipeline as ItemLogPageDialog's own
    // (long-press there, tap here -- see this composable's doc comment
    // for why the trigger differs) -- capture/crop staging kept local
    // to this Composable, same reasoning as that one.
    var showImageChangeMenu by remember { mutableStateOf(false) }
    var pendingCropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun clearCropState() {
        pendingCropSourceUri = null
        cropSourceBitmap = null
    }

    LaunchedEffect(pendingCropSourceUri) {
        val uri = pendingCropSourceUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeBitmapWithCorrectOrientation(context, uri)
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap == null) {
            clearCropState()
        } else {
            cropSourceBitmap = bitmap
        }
    }

    fun uploadNewImage(bytes: ByteArray) {
        coroutineScope.launch {
            try {
                val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
                val scanResult = ApiClient.service.scanProductPhoto(part)
                onImageChanged(scanResult.imagePath)
            } catch (e: Exception) {
                // Surfaced via imageError, already threaded through from
                // the ViewModel's own catch block around updateRecipe.
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCropSourceUri = pendingCameraUri
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) pendingCropSourceUri = uri
    }

    fun launchCamera() {
        val file = java.io.File(context.cacheDir, "recipe_photo_${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    if (showImageChangeMenu) {
        AlertDialog(
            onDismissRequest = { showImageChangeMenu = false },
            title = { Text("Change photo") },
            text = { Text("Take a photo or pick one from your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    showImageChangeMenu = false
                    launchCamera()
                }) { Text("Take Photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageChangeMenu = false
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text("Choose from Gallery") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDeleteConfirm,
            title = { Text("Delete this ${if (recipe.recipeType == "meal") "meal" else "recipe"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !isDeleting) {
                    Text(if (isDeleting) "Deleting..." else "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConfirm) { Text("Cancel") }
            }
        )
    }

    if (itemForIngredientPicker != null) {
        ItemQuantityDialog(
            item = itemForIngredientPicker,
            quantityInput = ingredientQuantityInput,
            servingSizeId = ingredientServingSizeId,
            isSaving = isAddingIngredient,
            error = addIngredientError,
            confirmLabel = if (editingIngredientItemId != null) "Save" else "Add ingredient",
            onQuantityChange = onIngredientQuantityChange,
            onServingChange = onIngredientServingChange,
            onCreateNewServing = {},
            onConfirm = onConfirmAddIngredient,
            onDismiss = onDismissIngredientPicker,
            onRemove = if (editingIngredientItemId != null) onRemoveFromPicker else null
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clickable { showImageChangeMenu = true }
            ) {
                if (recipe.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + recipe.imagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(CatalogVisuals.backgroundFor(recipe.recipeType)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            CatalogVisuals.iconFor(recipe.recipeType),
                            contentDescription = null,
                            tint = CatalogVisuals.iconTint(),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                if (isUploadingImage) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            }
            if (imageError != null) {
                Text(
                    imageError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                if (isEditing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Edit ${if (recipe.recipeType == "meal") "meal" else "recipe"}", style = MaterialTheme.typography.titleMedium)
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
                    val focusManager = LocalFocusManager.current
                    OutlinedTextField(
                        value = editName,
                        onValueChange = onEditNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (recipe.recipeType == "meal") ImeAction.Done else ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = { focusManager.clearFocus() }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Meals are always exactly one serving -- no field
                    // to edit, see this composable's own doc comment.
                    if (recipe.recipeType != "meal") {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(
                            value = editServings,
                            onValueChange = onEditServingsChange,
                            label = { Text("Servings") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (editError != null) {
                        Text(
                            editError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onCancelEdit, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                        Button(onClick = onSaveEdit, enabled = !isSavingEdit, modifier = Modifier.weight(1f)) {
                            Text(if (isSavingEdit) "Saving..." else "Save")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(recipe.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onEditClick) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit name and servings")
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        // Meals are always exactly one serving -- showing
                        // a servings count for that type would be
                        // showing a number that's never actually
                        // meaningful, see this composable's doc comment.
                        if (recipe.recipeType == "meal") {
                            "${recipe.totalsPerServing.kcal} Cal"
                        } else {
                            "${recipe.totalsPerServing.kcal} Cal / serving \u00b7 ${recipe.servings} servings total"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
                // When viewing a LOGGED INSTANCE (not browsing/editing
                // the global recipe), macros and ingredient quantities
                // should reflect the amount actually eaten (the logged
                // number of servings), not the recipe's flat per-
                // serving reference values or its full stored
                // ingredient list (see design discussion: "we should be
                // showing the macros and ingredient quantities for the
                // amount of servings, not the ingredient quantities for
                // the entire recipe"). totalsPerServing is already
                // per-ONE-serving, so multiplying by the logged
                // quantity directly gives the actual amount consumed;
                // each ingredient's stored quantity represents the
                // WHOLE recipe (recipe.servings servings' worth), so
                // that one needs dividing by recipe.servings first.
                // Browsing/editing the global recipe (logInstanceId ==
                // null) shows the unscaled per-serving/whole-recipe
                // reference values, same as before.
                val loggedServings = if (logInstanceId != null) quantityInput.toDoubleOrNull() ?: 1.0 else 1.0
                val recipeServingsCount = recipe.servings.toDoubleOrNull() ?: 1.0
                val ingredientScaleFactor = if (logInstanceId != null && recipeServingsCount > 0) {
                    loggedServings / recipeServingsCount
                } else {
                    1.0
                }
                // Same colored progress-bar treatment as the item info
                // screen's LogMacroBar (see design discussion: "i wanted
                // recipes and meals to show macro info the same way
                // items do with the colored progress bars and all"),
                // reusing that exact shared composable rather than a
                // separate flat-chip display, so the two screens stay
                // visually consistent.
                LogMacroBar(
                    "Protein", (recipe.totalsPerServing.proteinG * loggedServings).roundToInt(),
                    goalProtein, MacroColors.Protein
                )
                LogMacroBar(
                    "Fat", (recipe.totalsPerServing.fatG * loggedServings).roundToInt(),
                    goalFat, MacroColors.Fat,
                    subLabel = "Saturated fat: ${(recipe.totalsPerServing.saturatedFatG * loggedServings).roundToInt()}g"
                )
                LogMacroBar(
                    "Carbs", (recipe.totalsPerServing.carbsG * loggedServings).roundToInt(),
                    goalCarbs, MacroColors.Carbs,
                    subLabel = "Sugar: ${(recipe.totalsPerServing.sugarG * loggedServings).roundToInt()}g"
                )
                LogMacroBar(
                    "Fiber", (recipe.totalsPerServing.fiberG * loggedServings).roundToInt(),
                    goalFiber, MacroColors.Fiber
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Sodium: ${(recipe.totalsPerServing.sodiumMg * loggedServings).roundToInt()}mg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))
                Text("Ingredients", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                if (recipe.ingredients.isEmpty()) {
                    Text(
                        "No ingredients listed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recipe.ingredients.forEach { ingredient ->
                        IngredientRow(
                            ingredient = ingredient,
                            isEditing = isEditing,
                            scaleFactor = ingredientScaleFactor,
                            onClick = { if (isEditing) onOpenIngredientEdit(ingredient) },
                            onSwipeRemove = { onRemoveBySwipe(ingredient) }
                        )
                    }
                }

                if (isEditing) {
                    // Genuinely the same search-list behavior as
                    // everywhere else in the app (see design
                    // discussion: "can we genuinely copy the way the
                    // lists we already have behave") -- recent items
                    // shown blank-query, debounced search once typing
                    // starts, not a simplified "only show results once
                    // you type" version.
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
                    Text("Add ingredient", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                    OutlinedTextField(
                        value = ingredientSearchQuery,
                        onValueChange = onIngredientSearchQueryChange,
                        label = { Text("Search for an ingredient") },
                        singleLine = true,
                        trailingIcon = if (ingredientSearchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { onIngredientSearchQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ItemResultsList(
                        items = if (ingredientSearchQuery.isBlank()) recentIngredientItems else ingredientSearchResults,
                        isLoading = if (ingredientSearchQuery.isBlank()) isLoadingRecentIngredientItems else isSearchingIngredients,
                        emptyMessage = if (ingredientSearchQuery.isBlank()) "No items yet" else "No matches",
                        quickLoggingItemId = null,
                        lastLoggedAmounts = emptyMap(),
                        onItemClick = onOpenIngredientPicker,
                        onQuickAddClick = { itemId ->
                            val results = if (ingredientSearchQuery.isBlank()) recentIngredientItems else ingredientSearchResults
                            results.find { it.itemId == itemId }?.let(onOpenIngredientPicker)
                        }
                    )

                    // Small tappable text under the add-ingredient
                    // section (see design discussion: "under the add
                    // button, there should be smaller tappable text to
                    // delete meal/recipe").
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = onRequestDelete) {
                        Text(
                            "Delete this ${if (recipe.recipeType == "meal") "meal" else "recipe"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))

                    if (recipe.recipeType == "meal") {
                        Text(
                            "Logs this meal's original ingredients and amounts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedTextField(
                            value = quantityInput,
                            onValueChange = onQuantityChange,
                            label = { Text("Servings") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (error != null) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
                    Button(onClick = onConfirm, enabled = !isLogging, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (isLogging) {
                                "Saving..."
                            } else if (logInstanceId != null) {
                                "Save"
                            } else {
                                "Log this ${if (recipe.recipeType == "meal") "meal" else "recipe"}"
                            }
                        )
                    }
                    // logInstanceId can only ever be set for
                    // recipe_type="recipe" (a "meal" log always expands
                    // into per-ingredient item logs instead, see
                    // MealDetailUiState.recipeLogInstanceId's doc
                    // comment) -- deletes just THIS logged instance, not
                    // the recipe itself (that's "Delete this recipe"
                    // in edit mode above).
                    if (logInstanceId != null) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = onDeleteInstance, enabled = !isLogging) {
                            Text("Delete this log", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 20.dp))
            }
        }

        if (cropSourceBitmap != null) {
            CropDialog(
                sourceBitmap = cropSourceBitmap!!,
                onCropped = { cropped ->
                    val stream = java.io.ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    clearCropState()
                    uploadNewImage(stream.toByteArray())
                },
                onCancel = { clearCropState() }
            )
        }
    }
}

/** Same image/name/quantity-or-serving/kcal layout as Journal's LogRow
 * (see design discussion: "i'd like the ingredient list to be the same
 * as the one in the journal"), and now the same INTERACTION pattern too
 * -- tappable (opens the item's info to adjust quantity/serving or
 * remove it entirely, edit mode only) and swipe-left-to-delete (same
 * SwipeToDismissBox pattern as LogRow, see that composable's own doc
 * comment for the "always snap back" reasoning this reuses). Not
 * swipeable/tappable outside edit mode -- this is just an informational
 * display when only logging, not editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientRow(
    ingredient: RecipeIngredient,
    isEditing: Boolean,
    // Scales the displayed quantity/kcal -- 1.0 when browsing/editing
    // the global recipe (shows the ingredient as actually stored), a
    // fraction/multiple of that when viewing a logged instance (shows
    // the amount that was actually eaten, see RecipeInfoScreen's own
    // comment on ingredientScaleFactor for the exact math). Never
    // changes what's stored, purely a display-time scaling.
    scaleFactor: Double = 1.0,
    onClick: () -> Unit,
    onSwipeRemove: () -> Unit
) {
    val rowContent = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .then(if (isEditing) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CatalogVisuals.backgroundFor("product")),
                contentAlignment = Alignment.Center
            ) {
                if (ingredient.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + ingredient.imagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        CatalogVisuals.iconFor("product"),
                        contentDescription = null,
                        tint = CatalogVisuals.iconTint(),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ingredient.itemName, style = MaterialTheme.typography.bodyLarge)
                // Same servingSizeName-or-grams fallback as LogRow, plus
                // the same gram-equivalent suffix -- see that
                // composable's own doc comment for the reasoning. All
                // scaled by scaleFactor first (see this composable's own
                // doc comment on that param).
                val ingredientQuantityValue = ingredient.quantity.toDoubleOrNull()?.let { it * scaleFactor }
                val ingredientServingWeightG = ingredient.servingSizeWeightG?.toDoubleOrNull()
                val ingredientGramsSuffix = if (
                    ingredient.servingSizeName != null && ingredientQuantityValue != null && ingredientServingWeightG != null
                ) {
                    " (${formatQuantity(ingredientQuantityValue * ingredientServingWeightG)}g)"
                } else {
                    ""
                }
                Text(
                    "${ingredientQuantityValue?.let { formatQuantity(it) } ?: ingredient.quantity}" +
                        (ingredient.servingSizeName?.let { " $it$ingredientGramsSuffix" } ?: "g"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("${(ingredient.kcal * scaleFactor).roundToInt()} Cal", style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (!isEditing) {
        rowContent()
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeRemove()
            }
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Color.White)
            }
        }
    ) {
        rowContent()
    }
}

/**
 * Full-page item log screen -- same hero-image/scrollable-card/macro-bar
 * treatment used throughout this file for full-page item/recipe info,
 * reused here for an item that ISN'T logged yet (per design discussion:
 * "you almost had it before, you just had to add the unit/serving
 * picker to the existing page"). Unlike editing an already-logged item,
 * macros here ARE live-recomputed as you change quantity/unit
 * (item.kcal100g etc. * grams/100) since there's no saved log snapshot
 * yet to fall back on.
 *
 * Unit is either raw grams or one of the item's named ServingSizes. With
 * a serving selected, the quantity typed is a MULTIPLIER of that
 * serving's weight (2 x "slice" @ 37.5g = 75g) -- mirrors
 * LoggableEntryBase's quantity semantics on the backend exactly.
 *
 * The dropdown's last option, "+ Create new serving", opens
 * CreateServingDialog (backend already supported this via POST
 * /items/{id}/serving-sizes -- just wasn't wired up client-side before).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ItemLogPageDialog(
    item: Item,
    goalFat: Int,
    goalProtein: Int,
    goalCarbs: Int,
    goalFiber: Int,
    quantityInput: String,
    servingSizeId: Int?,
    isLogging: Boolean,
    error: String?,
    // Non-null when editing an already-logged item (see
    // MealDetailViewModel.editingLogId) -- shows "Save changes" +
    // a Delete button instead of "Add to meal".
    isEditing: Boolean,
    onQuantityChange: (String) -> Unit,
    onServingChange: (Int?) -> Unit,
    onCreateNewServing: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onImageUpdated: (Item) -> Unit,
    onEditClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Long-press-to-change-photo -- self-contained crop staging, same
    // pattern AddItemScreen uses for its own capture/gallery/crop flow
    // (see that file's pendingCropSourceUri/cropSourceBitmap/
    // onCropComplete). Kept local to this Composable rather than in the
    // ViewModel since it's purely a UI-side capture pipeline before
    // anything gets uploaded.
    var showImageChangeMenu by remember { mutableStateOf(false) }
    var pendingCropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUploadingImage by remember { mutableStateOf(false) }
    var imageUpdateError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun clearCropState() {
        pendingCropSourceUri = null
        cropSourceBitmap = null
    }

    LaunchedEffect(pendingCropSourceUri) {
        val uri = pendingCropSourceUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeBitmapWithCorrectOrientation(context, uri)
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap == null) {
            clearCropState()
            imageUpdateError = "Couldn't read that image"
        } else {
            cropSourceBitmap = bitmap
        }
    }

    fun uploadNewImage(bytes: ByteArray) {
        isUploadingImage = true
        imageUpdateError = null
        coroutineScope.launch {
            try {
                val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
                val scanResult = ApiClient.service.scanProductPhoto(part)
                val updatedItem = ApiClient.service.updateItemImage(
                    item.itemId,
                    com.mealtracker.android.network.models.ItemImagePathUpdateRequest(scanResult.imagePath)
                )
                isUploadingImage = false
                onImageUpdated(updatedItem)
            } catch (e: Exception) {
                isUploadingImage = false
                imageUpdateError = e.message ?: "Couldn't update the photo"
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) pendingCropSourceUri = pendingCameraUri
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) pendingCropSourceUri = uri
    }

    fun launchCamera() {
        val file = java.io.File(context.cacheDir, "item_photo_${System.currentTimeMillis()}.jpg")
        // Many camera apps (including stock/AOSP camera on some OEMs)
        // silently fail to write into a content:// Uri if the
        // underlying file doesn't already exist -- File(...) alone only
        // builds a path reference, it doesn't touch the filesystem.
        // This was the actual bug behind "retake doesn't work for
        // changing an existing item's photo" (this is the only place in
        // the app using ActivityResultContracts.TakePicture() at all --
        // AddItemScreen's camera capture goes through CameraX's live
        // preview instead, which never hit this).
        file.createNewFile()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val bitmapToCrop = cropSourceBitmap
    // CropDialog itself now renders inline where the Dialog's content Box
    // is built below (as a later sibling of the main Column there), not
    // here -- see CropDialog's doc comment for why it needs a Box
    // sibling structure to overlay correctly now that it's not
    // self-wrapping in its own Dialog window anymore.

    if (showImageChangeMenu) {
        AlertDialog(
            onDismissRequest = { showImageChangeMenu = false },
            title = { Text("Change photo") },
            text = { Text("Retake a photo or pick one from your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    showImageChangeMenu = false
                    launchCamera()
                }) { Text("Retake Photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageChangeMenu = false
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text("Choose from Gallery") }
            }
        )
    }

    var unitMenuExpanded by remember { mutableStateOf(false) }
    val selectedServing = item.servingSizes.find { it.id == servingSizeId }
    val unitLabel = selectedServing?.name ?: "g"

    val quantityValue = quantityInput.toDoubleOrNull()
    val effectiveGrams = when {
        quantityValue == null -> 0.0
        selectedServing != null -> quantityValue * (selectedServing.weightG.toDoubleOrNull() ?: 0.0)
        else -> quantityValue
    }
    fun per100(value: String?): Int = ((value?.toDoubleOrNull() ?: 0.0) * effectiveGrams / 100.0).roundToInt()

    // No Dialog() wrapper -- this used to have one, with the same
    // "force the window to MATCH_PARENT via a SideEffect" workaround
    // CropDialog itself used to rely on, and removed, because that
    // workaround was found unreliable on some devices (see CropDialog's
    // own doc comment for that history). CropDialog renders correctly
    // as a plain composable inside HERE, but was still inheriting the
    // SAME unreliable window from ITS OWN enclosing Dialog -- CropDialog
    // being correct doesn't help if the window it's placed in isn't
    // reliably full-screen (see design discussion: "how come it happens
    // in multiple places... I thought this is defined inside the crop
    // dialog itself" -- this is exactly why: the shared component was
    // fine, but one of its three embedding call sites still wrapped it
    // in the same kind of window that was already known to be
    // unreliable). Rendered as a plain composable now, same as
    // AddItemScreen's own capture/crop flow -- the CALLER
    // (MealDetailScreen) is responsible for layering this as a later
    // Box sibling so it draws on top of everything else, matching the
    // exact same pattern already used for CropDialog itself.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showImageChangeMenu = true }
                    )
            ) {
                if (item.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + item.imagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(CatalogVisuals.backgroundFor(item.type)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            CatalogVisuals.iconFor(item.type),
                            contentDescription = null,
                            tint = CatalogVisuals.iconTint(),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                if (isUploadingImage) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
            }
            if (imageUpdateError != null) {
                Text(
                    imageUpdateError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onEditClick) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit name and macros")
                    }
                }
                if (item.brand != null) {
                    Text(
                        item.brand,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = onQuantityChange,
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))

                    Box {
                        androidx.compose.material3.AssistChip(
                            onClick = { unitMenuExpanded = true },
                            label = { Text(unitLabel) }
                        )
                        DropdownMenu(expanded = unitMenuExpanded, onDismissRequest = { unitMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("g") },
                                onClick = { onServingChange(null); unitMenuExpanded = false }
                            )
                            item.servingSizes.forEach { serving ->
                                DropdownMenuItem(
                                    text = { Text("${serving.name} (${serving.weightG}g)") },
                                    onClick = { onServingChange(serving.id); unitMenuExpanded = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Create new serving") },
                                onClick = { unitMenuExpanded = false; onCreateNewServing() }
                            )
                        }
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                Text(
                    "${per100(item.kcal100g)} Cal for ${effectiveGrams.roundToInt()}g",
                    style = MaterialTheme.typography.titleMedium
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))
                Text("Share of this meal's goal", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                LogMacroBar("Protein", per100(item.protein100g), goalProtein, MacroColors.Protein)
                LogMacroBar(
                    "Fat", per100(item.fat100g), goalFat, MacroColors.Fat,
                    subLabel = "Saturated fat: ${per100(item.saturatedFat100g)}g"
                )
                LogMacroBar(
                    "Carbs", per100(item.carbs100g), goalCarbs, MacroColors.Carbs,
                    subLabel = "Sugar: ${per100(item.sugar100g)}g"
                )
                LogMacroBar("Fiber", per100(item.fiber100g), goalFiber, MacroColors.Fiber)

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Sodium: ${per100(item.sodiumMg100g)}mg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))

                Button(onClick = onConfirm, enabled = !isLogging, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            isLogging && isEditing -> "Saving..."
                            isLogging -> "Adding..."
                            isEditing -> "Save changes"
                            else -> "Add to meal"
                        }
                    )
                }
                if (isEditing) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }

        if (bitmapToCrop != null) {
            CropDialog(
                sourceBitmap = bitmapToCrop,
                onCropped = { cropped ->
                    val stream = java.io.ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    clearCropState()
                    uploadNewImage(stream.toByteArray())
                },
                onCancel = { clearCropState() }
            )
        }
    }
}

/** Reached from ItemLogPageDialog's pencil button -- edits the item's
 * name and per-100g macros. Salt (g) shown/entered instead of sodium,
 * same convention as AddItemScreen's form -- converted to sodium mg at
 * save time (see MealDetailViewModel.saveItemEdit). */
@Composable
private fun EditItemDialog(
    name: String,
    kcal: String,
    protein: String,
    carbs: String,
    fat: String,
    fiber: String,
    sugar: String,
    saturatedFat: String,
    saltG: String,
    countsAsAddedSugar: Boolean?,
    isSaving: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onKcalChange: (String) -> Unit,
    onProteinChange: (String) -> Unit,
    onCarbsChange: (String) -> Unit,
    onFatChange: (String) -> Unit,
    onFiberChange: (String) -> Unit,
    onSugarChange: (String) -> Unit,
    onSaturatedFatChange: (String) -> Unit,
    onSaltChange: (String) -> Unit,
    onCyclesCountsAsAddedSugar: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val focusManager = LocalFocusManager.current
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Per 100g",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                EditNumberField("Calories", kcal, onKcalChange)
                EditNumberField("Fat (g)", fat, onFatChange)
                EditNumberField("Saturated Fat (g)", saturatedFat, onSaturatedFatChange)
                EditNumberField("Carbs (g)", carbs, onCarbsChange)
                EditNumberField("Sugar (g)", sugar, onSugarChange)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                // 3-state toggle, not a plain checkbox -- "use the
                // default heuristic" is a real, distinct state (see
                // design discussion: "my third highest ranking added
                // sugar source is frozen berry mix... this is silly").
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCyclesCountsAsAddedSugar)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Counts as added sugar",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when (countsAsAddedSugar) {
                            null -> "Auto"
                            true -> "Yes"
                            false -> "No"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "\"Auto\" counts scanned/manual items but not raw USDA ingredients (e.g. a banana). Override if this item is a whole food sold as a product, like frozen fruit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                EditNumberField("Fiber (g)", fiber, onFiberChange)
                EditNumberField("Protein (g)", protein, onProteinChange)
                EditNumberField("Salt (g)", saltG, onSaltChange, isLast = true)
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSaving) {
                Text(if (isSaving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditNumberField(label: String, value: String, onValueChange: (String) -> Unit, isLast: Boolean = false) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = if (isLast) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { focusManager.clearFocus() }
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}

@Composable
private fun LogMacroBar(label: String, amountG: Int, goalG: Int, color: Color, subLabel: String? = null) {
    val fraction = if (goalG > 0) (amountG.toFloat() / goalG.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${amountG}g / ${goalG}g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Smaller text under the relevant macro (saturated fat under
        // fat, sugar under carbs) -- see design discussion: "could we
        // start showing sugar, saturated fats and sodium on the item
        // and recipe/meal info screens". No goal/progress bar of its
        // own, just informational.
        if (subLabel != null) {
            Text(
                subLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogRow(log: Log, onClick: () -> Unit, onDelete: () -> Unit) {
    // Swipe LEFT (EndToStart) to delete -- StartToEnd disabled so a
    // stray right-swipe doesn't accidentally trigger it, per design
    // discussion ("swiping the item to the left").
    //
    // confirmValueChange always returns false here, deliberately -- it
    // used to return true on EndToStart, which let the box commit
    // internally to its own "dismissed" visual state immediately,
    // before onDelete()'s actual network call had even resolved. If
    // that call was slow, or failed, or the log list re-rendered for
    // any unrelated reason in between, the row could end up stuck: still
    // present in the list, but with its swipe state already "confirmed
    // dismissed", so it rendered in the fully-swiped-away position and
    // stopped responding to further swipes (see design discussion:
    // "swiping left to delete an item doesn't always work correctly and
    // gets stuck"). Returning false instead means the box ALWAYS snaps
    // back to resting after any swipe attempt -- onDelete() still fires
    // as a side effect, but whether the row actually disappears is
    // driven purely by it being removed from state.logs once the delete
    // succeeds (ordinary recomposition), not by the swipe box's own
    // internal state. If the delete fails, the row is simply back to
    // normal and swipeable again, instead of stuck.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        // Log doesn't carry the source item's/recipe's
                        // type (product/ingredient/recipe/meal), only
                        // denormalized name/image -- recipeId presence
                        // is the only signal available here, so this can
                        // only distinguish "some recipe" from "some
                        // item", not the finer type. Good enough for a
                        // fallback icon; the search results list (which
                        // DOES have the real type) is more precise.
                        CatalogVisuals.backgroundFor(if (log.recipeId != null) "recipe" else "product")
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (log.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + log.imagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        CatalogVisuals.iconFor(if (log.recipeId != null) "recipe" else "product"),
                        contentDescription = null,
                        tint = CatalogVisuals.iconTint(),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(log.itemName ?: log.recipeName ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
                // Recipe-based logs: quantity is "number of recipe
                // servings consumed" (see LoggableEntryBase's quantity-
                // semantics doc comment on the backend), never grams --
                // showing "1g" here was wrong, it should read "1
                // serving". Item-based logs keep the existing serving-
                // name-or-grams display (see that branch's own doc
                // comment for the bug it avoids repeating).
                val quantityDisplay = log.quantity.toDoubleOrNull()?.let { formatQuantity(it) } ?: log.quantity
                // When a custom serving was used, follows it up with the
                // gram equivalent in parentheses (e.g. "2 slices (75g)")
                // -- quantity alone doesn't tell you how much that
                // actually was in grams, same reasoning as showing the
                // serving name itself instead of just falling back to a
                // meaningless raw quantity.
                val quantityValue = log.quantity.toDoubleOrNull()
                val servingWeightG = log.servingSizeWeightG?.toDoubleOrNull()
                val gramsSuffix = if (log.servingSizeName != null && quantityValue != null && servingWeightG != null) {
                    " (${formatQuantity(quantityValue * servingWeightG)}g)"
                } else {
                    ""
                }
                Text(
                    if (log.recipeId != null) {
                        val servings = log.quantity.toDoubleOrNull() ?: 1.0
                        "$quantityDisplay ${if (servings == 1.0) "serving" else "servings"}"
                    } else {
                        "$quantityDisplay" + (log.servingSizeName?.let { " $it$gramsSuffix" } ?: "g")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("${log.kcalLogged} Cal", style = MaterialTheme.typography.bodyLarge)
        }
    }
}