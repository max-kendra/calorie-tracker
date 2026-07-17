package com.mealtracker.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.ui.components.LiveBarcodeScannerView
import com.mealtracker.android.ui.components.MacroRingsRow
import com.mealtracker.android.ui.components.MealVisuals
import kotlinx.coroutines.launch
import java.time.LocalDate

private val WhiteCardColors @Composable get() = CardDefaults.cardColors(containerColor = Color.White)
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
 *     is a pill shape (SEARCH_BAR_SHAPE). Barcode renders the live
 *     camera IN the sheet; an unmatched code falls back to a button
 *     that opens the existing full-screen scan/OCR flow, since that
 *     multi-step path isn't rebuilt inline yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailScreen(
    date: LocalDate,
    mealType: String,
    viewModel: MealDetailViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNavigateToAddItem: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val heroColor = MealVisuals.backgroundFor(mealType)

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
        sheetContainerColor = Color.White,
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
                        selected = state.sheetMode == AddItemSheetMode.SEARCH,
                        onClick = { selectMethod(AddItemSheetMode.SEARCH) }
                    )
                    AddMethodIcon(
                        Icons.Filled.QrCodeScanner, "Barcode",
                        selected = state.sheetMode == AddItemSheetMode.BARCODE,
                        onClick = { selectMethod(AddItemSheetMode.BARCODE) }
                    )
                }

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

                when (state.sheetMode) {
                    AddItemSheetMode.SEARCH -> {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            label = { Text("Search for a food") },
                            shape = SEARCH_BAR_SHAPE,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                        // Defaults to recently-logged items when the
                        // query is blank -- this IS the old "Saved" tab,
                        // now just the empty-query state of Search.
                        val showingRecent = state.searchQuery.isBlank()
                        ItemResultsList(
                            items = if (showingRecent) state.recentItems else state.searchResults,
                            isLoading = if (showingRecent) state.isLoadingRecentItems else state.isSearching,
                            emptyMessage = if (showingRecent) "Nothing logged recently" else "No matches",
                            quickLoggingItemId = state.quickLoggingItemId,
                            onItemClick = { viewModel.logItemQuickly(it) }
                        )
                    }
                    AddItemSheetMode.BARCODE -> BarcodeSheetContent(
                        barcodeNotFound = state.barcodeNotFound,
                        onBarcodeDetected = { viewModel.onBarcodeScanned(it) },
                        onUseFullScanFlow = onNavigateToAddItem
                    )
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
                            onOpenSaveMealDialog = { viewModel.openSaveMealDialog() }
                        )
                    }
                }
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
    onOpenSaveMealDialog: () -> Unit
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
                    state.logs.forEach { log -> LogRow(log) }
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
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(background, CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
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
    onItemClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
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
                            .clickable(enabled = quickLoggingItemId == null) { onItemClick(item.itemId) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            if (item.brand != null) {
                                Text(
                                    item.brand,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (quickLoggingItemId == item.itemId) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = "Log this item")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** In-sheet barcode scanner -- same permission pattern as AddItemScreen's
 * full-screen version. Detected codes go straight to
 * MealDetailViewModel.onBarcodeScanned, which either quick-logs a match
 * or surfaces barcodeNotFound. */
@Composable
private fun BarcodeSheetContent(
    barcodeNotFound: String?,
    onBarcodeDetected: (String) -> Unit,
    onUseFullScanFlow: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (!hasCameraPermission) {
            Text("Camera permission is needed to scan a barcode.", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Permission")
            }
        } else {
            // Vertical rectangle (portrait aspect) rather than a fixed
            // height -- spans most of the sheet's width/height
            // proportionally regardless of screen size, per design
            // discussion, instead of the previous fixed 240dp height
            // (which read as a short, wide letterbox).
            LiveBarcodeScannerView(
                onBarcodeDetected = onBarcodeDetected,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(16.dp))
            )
        }

        if (barcodeNotFound != null) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
            Text(barcodeNotFound, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = onUseFullScanFlow) {
                Text("Use the full scan/label flow instead")
            }
        }
    }
}

@Composable
private fun LogRow(log: Log) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(log.itemName ?: log.recipeName ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
        Text("${log.kcalLogged} Cal", style = MaterialTheme.typography.bodyLarge)
    }
}