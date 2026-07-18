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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.health.HealthConnectPreferences
import com.mealtracker.android.ui.components.CatalogVisuals
import com.mealtracker.android.ui.components.CropDialog
import com.mealtracker.android.ui.components.decodeBitmapWithCorrectOrientation
import com.mealtracker.android.ui.components.MacroColors
import com.mealtracker.android.ui.components.MacroRingsRow
import com.mealtracker.android.ui.components.MealVisuals
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
    // function (logItemQuickly, confirmLogItemQuantity, saveLogQuantity,
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
    LaunchedEffect(state.logs, date, mealType) {
        if (!HealthConnectPreferences.isNutritionExportEnabled(healthConnectContext)) return@LaunchedEffect
        if (!HealthConnectManager.isAvailable(healthConnectContext)) return@LaunchedEffect
        if (!HealthConnectManager.hasAllPermissions(healthConnectContext)) return@LaunchedEffect

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

    val selectedLog = state.selectedLog
    if (selectedLog != null) {
        LogDetailDialog(
            log = selectedLog,
            goalKcal = state.goalKcal,
            goalFat = state.goalFat,
            goalProtein = state.goalProtein,
            goalCarbs = state.goalCarbs,
            goalFiber = state.goalFiber,
            editQuantityInput = state.editQuantityInput,
            isSaving = state.isSavingLogEdit,
            error = state.logEditError,
            onQuantityChange = { viewModel.updateEditQuantityInput(it) },
            onSave = { viewModel.saveLogQuantity() },
            onDelete = { viewModel.deleteLog(selectedLog.id) },
            onDismiss = { viewModel.dismissLogDetail() }
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
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

                when (state.sheetMode) {
                    AddItemSheetMode.SEARCH -> {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            label = { Text("Search for a food") },
                            shape = SEARCH_BAR_SHAPE,
                            singleLine = true,
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
                                    label = { Text(filter.label) }
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
                                onQuickAddClick = { recipe -> viewModel.logRecipeQuickly(recipe) }
                            )
                        } else {
                            ItemResultsList(
                                items = if (showingRecent) state.recentItems else state.searchResults,
                                isLoading = if (showingRecent) state.isLoadingRecentItems else state.isSearching,
                                emptyMessage = if (showingRecent) "No items yet" else "No matches",
                                quickLoggingItemId = state.quickLoggingItemId,
                                lastLoggedAmounts = state.lastLoggedAmounts,
                                onItemClick = { item -> viewModel.openItemQuantityPicker(item) },
                                onQuickAddClick = { itemId -> viewModel.logItemQuickly(itemId) }
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
                }

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
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

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
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
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

/** "142 Cal, 100g" preview of what tapping "+" would actually log for
 * this item right now -- see MealDetailViewModel.LoggedAmount. Returns
 * null if there's not enough info to compute a preview (no kcal_100g on
 * the item, or a remembered serving that's since been deleted). */
private fun quickAddPreview(item: Item, remembered: LoggedAmount?): String? {
    val quantity = remembered?.quantity ?: 100.0
    val grams = if (remembered?.servingSizeId != null) {
        val serving = item.servingSizes.find { it.id == remembered.servingSizeId } ?: return null
        quantity * (serving.weightG.toDoubleOrNull() ?: return null)
    } else {
        quantity
    }
    val kcal = item.kcal100g?.toDoubleOrNull()?.times(grams / 100.0) ?: return null
    return "${kcal.roundToInt()} Cal, ${"%.0f".format(grams)}g"
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
    onQuickAddClick: (Recipe) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState())
    ) {
        when {
            isLoading -> {
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
                            .clickable(enabled = quickLoggingRecipeId == null) { onQuickAddClick(recipe) }
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

/** Results list backing Search (recent items when blank, search results
 * once typing) -- bounded height + its own scroll so a long list
 * doesn't fight the sheet's own drag gesture. */
@Composable
private fun ItemResultsList(
    items: List<Item>,
    isLoading: Boolean,
    emptyMessage: String,
    quickLoggingItemId: Int?,
    lastLoggedAmounts: Map<Int, LoggedAmount>,
    onItemClick: (Item) -> Unit,
    onQuickAddClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp)
            .verticalScroll(rememberScrollState())
    ) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            items.isEmpty() -> {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tapping the ROW opens the quantity/serving
                            // picker (see MealDetailViewModel.
                            // openItemQuantityPicker) -- the separate "+"
                            // button below is still the flat-quantity
                            // quick-add shortcut, kept for when you just
                            // want the default fast without picking
                            // anything.
                            .clickable(enabled = quickLoggingItemId == null) { onItemClick(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CatalogVisuals.backgroundFor(item.type)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (item.imagePath != null) {
                                coil3.compose.AsyncImage(
                                    model = com.mealtracker.android.BuildConfig.BASE_URL + item.imagePath,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    CatalogVisuals.iconFor(item.type),
                                    contentDescription = null,
                                    tint = CatalogVisuals.iconTint(),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            if (item.brand != null) {
                                Text(
                                    item.brand,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // What tapping "+" would actually log -- the
                            // last quantity/serving used for this item,
                            // or 100g the first time (see
                            // MealDetailViewModel.LoggedAmount).
                            val preview = quickAddPreview(item, lastLoggedAmounts[item.itemId])
                            if (preview != null) {
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (quickLoggingItemId == item.itemId) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { onQuickAddClick(item.itemId) }) {
                                Icon(Icons.Filled.Add, contentDescription = "Quick add 100g")
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
 * Full-page item log screen -- same hero-image/scrollable-card/macro-bar
 * treatment as LogDetailDialog below, reused here for an item that
 * ISN'T logged yet (per design discussion: "you almost had it before,
 * you just had to add the unit/serving picker to the existing page").
 * Unlike LogDetailDialog, macros here ARE live-recomputed as you change
 * quantity/unit (item.kcal100g etc. * grams/100) since there's no saved
 * log snapshot yet to fall back on.
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
                        .background(Color.White.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close")
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
                LogMacroBar("Fat", per100(item.fat100g), goalFat, MacroColors.Fat)
                LogMacroBar("Carbs", per100(item.carbs100g), goalCarbs, MacroColors.Carbs)
                LogMacroBar("Fiber", per100(item.fiber100g), goalFiber, MacroColors.Fiber)

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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Per 100g",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                EditNumberField("Calories", kcal, onKcalChange)
                EditNumberField("Protein (g)", protein, onProteinChange)
                EditNumberField("Fat (g)", fat, onFatChange)
                EditNumberField("Saturated Fat (g)", saturatedFat, onSaturatedFatChange)
                EditNumberField("Carbs (g)", carbs, onCarbsChange)
                EditNumberField("Sugar (g)", sugar, onSugarChange)
                EditNumberField("Fiber (g)", fiber, onFiberChange)
                EditNumberField("Salt (g)", saltG, onSaltChange)
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
private fun EditNumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}

/** Reached from ItemLogPageDialog's unit dropdown -- backend already had
 * full CRUD for this (POST/PATCH/DELETE /items/{id}/serving-sizes),
 * just no client UI to reach it. */
@Composable
private fun CreateServingDialog(
    name: String,
    weightG: String,
    isCreating: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New serving") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name (e.g. \"slice\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = weightG,
                    onValueChange = onWeightChange,
                    label = { Text("Weight (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isCreating) {
                Text(if (isCreating) "Creating..." else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Full-screen log detail: hero image up top, scrollable card below with
 * kcal for the CURRENTLY SAVED quantity, an editable quantity field, and
 * macro progress bars shown as a fraction of the MEAL's goal (not the
 * day's) -- matches design reference. Deliberately does not include
 * "Customize" or "Input and benefits" sections from that reference, per
 * design discussion ("ignore" those).
 *
 * KNOWN SIMPLIFICATION: the kcal/macro numbers shown reflect the log's
 * last-SAVED snapshot, not a live recompute as you type a new quantity
 * in the field below -- there's no client-side per-gram macro data to
 * recompute against without a round-trip. They update once you tap Save
 * and the meal reloads. A live preview would need either shipping the
 * item's per-100g macros down to this dialog, or a debounced preview
 * call to the backend -- not done here.
 */
@Composable
private fun LogDetailDialog(
    log: Log,
    goalKcal: Int,
    goalFat: Int,
    goalProtein: Int,
    goalCarbs: Int,
    goalFiber: Int,
    editQuantityInput: String,
    isSaving: Boolean,
    error: String?,
    onQuantityChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            window?.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                if (log.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + log.imagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // This dialog only ever shows recipe-based logs (see
                    // its own doc comment) -- "recipe" is a reasonable
                    // fixed default here since Log doesn't carry
                    // recipe_type (recipe vs meal) the way a full Recipe
                    // object would.
                    Box(
                        modifier = Modifier.fillMaxSize().background(CatalogVisuals.backgroundFor("recipe")),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            CatalogVisuals.iconFor("recipe"),
                            contentDescription = null,
                            tint = CatalogVisuals.iconTint(),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.White.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    log.itemName ?: log.recipeName ?: "Item",
                    style = MaterialTheme.typography.headlineSmall
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

                OutlinedTextField(
                    value = editQuantityInput,
                    onValueChange = onQuantityChange,
                    label = { Text("Quantity (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                Text(
                    "${log.kcalLogged} Cal for this quantity",
                    style = MaterialTheme.typography.titleMedium
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 20.dp))
                Text("Share of this meal's goal", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                LogMacroBar("Protein", log.proteinGLogged, goalProtein, MacroColors.Protein)
                LogMacroBar("Fat", log.fatGLogged, goalFat, MacroColors.Fat)
                LogMacroBar("Carbs", log.carbsGLogged, goalCarbs, MacroColors.Carbs)
                LogMacroBar("Fiber", log.fiberGLogged, goalFiber, MacroColors.Fiber)

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))

                Button(onClick = onSave, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isSaving) "Saving..." else "Save Quantity")
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun LogMacroBar(label: String, amountG: Int, goalG: Int, color: Color) {
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
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
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
                // Assumes grams -- true whenever serving_size_id is null,
                // which is every logging path this app currently has (all
                // go through quick-log or the item form, never a serving-
                // size picker). If a serving-size UI gets added later,
                // this needs to become serving-aware (see
                // LoggableEntryBase's quantity-semantics doc comment).
                Text(
                    "${log.quantity.toDoubleOrNull()?.let { formatQuantity(it) } ?: log.quantity}g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("${log.kcalLogged} Cal", style = MaterialTheme.typography.bodyLarge)
        }
    }
}