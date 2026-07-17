package com.mealtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.ui.components.CalendarPickerDialog
import com.mealtracker.android.ui.components.DonutChart
import com.mealtracker.android.ui.components.MacroColors
import com.mealtracker.android.ui.components.MacroRingsRow
import com.mealtracker.android.ui.components.MealVisuals
import com.mealtracker.android.ui.theme.JournalHeroPastel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Macro card collapses between these two heights (never all the way to
// 0) -- below MIN it would clip the compact bar view too, and the
// point of the bar view is that it stays visible while collapsed, not
// that it also disappears.
private val MACRO_CARD_MAX_HEIGHT = 150.dp
private val MACRO_CARD_MIN_HEIGHT = 64.dp
private val CARD_CORNER_RADIUS = 24.dp
private val WhiteCardColors @Composable get() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

/**
 * Journal screen. Layout, top to bottom:
 *   - pastel hero (status bar tinted to match): kcal ring (pinned,
 *     never collapses) + macro card (collapses on scroll between rings
 *     and a compact bar view -- see MacroBarsRow). Both live inside a
 *     bounded, non-scrolling pastel Box -- NOT the whole screen, so
 *     there's nothing pastel left over below the meal list.
 *   - meal list: a plain white Card, full-bleed (no side margins,
 *     rounded top corners only), independently scrollable, no dividers
 *     between rows -- sits directly on the app's default white
 *     background so it visually merges into the bottom nav bar with no
 *     visible seam.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = viewModel(),
    onNavigateToMealDetail: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val calendarMonthState by viewModel.calendarState.collectAsState()
    var showCalendarPicker by remember { mutableStateOf(false) }
    var pickerMonth by remember { mutableStateOf(YearMonth.now()) }

    // Reload whenever this screen comes back into view (e.g. returning
    // from Meal Detail after logging something) -- previously this only
    // ever loaded once via JournalViewModel's init{}, so anything logged
    // elsewhere never showed up here until a manual date-nav tap forced
    // a reload. loadJournal() itself already avoids a full-screen
    // loading flash when there's existing data to show (see that
    // function's own doc comment), so this is a quiet background
    // refresh, not a visible reset.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val currentState = viewModel.uiState.value
                if (currentState is JournalUiState.Success) {
                    viewModel.loadJournal(currentState.date)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val density = LocalDensity.current
    val maxMacroHeightPx = with(density) { MACRO_CARD_MAX_HEIGHT.toPx() }
    val minMacroHeightPx = with(density) { MACRO_CARD_MIN_HEIGHT.toPx() }
    var macroHeightPx by remember { mutableFloatStateOf(maxMacroHeightPx) }

    // Standard collapsing-header recipe: shrink the macro card first
    // (onPreScroll, before the meal list scrolls at all) when scrolling
    // up, and grow it back (onPostScroll, once the meal list is already
    // at its own top and has scroll delta left over) when scrolling down.
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta >= 0) return Offset.Zero
                val previous = macroHeightPx
                macroHeightPx = (macroHeightPx + delta).coerceIn(minMacroHeightPx, maxMacroHeightPx)
                return Offset(0f, macroHeightPx - previous)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta <= 0) return Offset.Zero
                val previous = macroHeightPx
                macroHeightPx = (macroHeightPx + delta).coerceIn(minMacroHeightPx, maxMacroHeightPx)
                return Offset(0f, macroHeightPx - previous)
            }
        }
    }

    when (val state = uiState) {
        is JournalUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is JournalUiState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Couldn't load your journal", style = MaterialTheme.typography.titleMedium)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { viewModel.loadJournal(LocalDate.now()) }) {
                    Text("Retry")
                }
            }
        }
        is JournalUiState.Success -> {
            Column(modifier = Modifier.fillMaxSize()) {
                // Bounded pastel region -- kcal ring + macro card ONLY.
                // Deliberately not fillMaxSize/weight(1f): its height is
                // just "however tall its content is", so nothing pastel
                // is left over below the meal list.
                Column(modifier = Modifier.fillMaxWidth().background(JournalHeroPastel)) {
                    // The pastel background itself extends all the way
                    // to y=0 (this Column has no top padding/inset
                    // handling) -- this spacer just pushes the readable
                    // content (kcal ring etc.) down below the status bar
                    // icons, without clipping the color itself. See
                    // AppNavHost's edgeToEdgeStatusBarRoutes for why this
                    // screen is responsible for its own top inset at all.
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    )
                    KcalHeroSection(totals = state.dailyTotals)

                    val ringFraction = ((macroHeightPx - minMacroHeightPx) / (maxMacroHeightPx - minMacroHeightPx))
                        .coerceIn(0f, 1f)
                    val macroHeightDp = with(density) { macroHeightPx.toDp() }

                    Card(
                        colors = WhiteCardColors,
                        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .height(macroHeightDp)
                            .clipToBounds()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.graphicsAlpha(ringFraction)) {
                                MacroRingsRow(
                                    fatEaten = state.dailyTotals.eatenFat, fatGoal = state.dailyTotals.goalFat,
                                    proteinEaten = state.dailyTotals.eatenProtein, proteinGoal = state.dailyTotals.goalProtein,
                                    carbsEaten = state.dailyTotals.eatenCarbs, carbsGoal = state.dailyTotals.goalCarbs,
                                    fiberEaten = state.dailyTotals.eatenFiber, fiberGoal = state.dailyTotals.goalFiber,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                                )
                            }
                            Box(modifier = Modifier.graphicsAlpha(1f - ringFraction).padding(horizontal = 20.dp)) {
                                MacroBarsRow(
                                    fatEaten = state.dailyTotals.eatenFat, fatGoal = state.dailyTotals.goalFat,
                                    proteinEaten = state.dailyTotals.eatenProtein, proteinGoal = state.dailyTotals.goalProtein,
                                    carbsEaten = state.dailyTotals.eatenCarbs, carbsGoal = state.dailyTotals.goalCarbs,
                                    fiberEaten = state.dailyTotals.eatenFiber, fiberGoal = state.dailyTotals.goalFiber,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(nestedScrollConnection)
                        .verticalScroll(rememberScrollState())
                ) {
                    Card(
                        colors = WhiteCardColors,
                        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            DateNavRow(
                                date = state.date,
                                onPrevDay = { viewModel.loadJournal(state.date.minusDays(1)) },
                                onNextDay = { viewModel.loadJournal(state.date.plusDays(1)) },
                                onDateClick = {
                                    pickerMonth = YearMonth.from(state.date)
                                    viewModel.loadCalendarMonth(pickerMonth)
                                    showCalendarPicker = true
                                }
                            )

                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

                            // No dividers between rows per design discussion --
                            // MealRow's own internal padding provides the
                            // separation instead.
                            state.buckets.forEach { bucket ->
                                MealRow(bucket, onClick = { onNavigateToMealDetail(bucket.mealType) })
                            }
                        }
                    }
                }
            }

            if (showCalendarPicker) {
                CalendarPickerDialog(
                    displayedMonth = pickerMonth,
                    selectedDate = state.date,
                    monthState = calendarMonthState,
                    onMonthChange = { newMonth ->
                        pickerMonth = newMonth
                        viewModel.loadCalendarMonth(newMonth)
                    },
                    onDateSelected = { date ->
                        viewModel.loadJournal(date)
                        showCalendarPicker = false
                        viewModel.clearCalendarState()
                    },
                    onDismiss = {
                        showCalendarPicker = false
                        viewModel.clearCalendarState()
                    }
                )
            }
        }
    }
}

@Composable
private fun KcalHeroSection(totals: DailyTotals) {
    val remaining = totals.goalKcal - totals.eatenKcal
    val kcalFraction = if (totals.goalKcal > 0) {
        (totals.eatenKcal.toFloat() / totals.goalKcal.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DonutChart(
            segments = listOf(kcalFraction to MaterialTheme.colorScheme.primary),
            diameter = 130.dp,
            strokeWidth = 12.dp,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            centerContent = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (remaining >= 0) {
                        Text("$remaining", style = MaterialTheme.typography.headlineMedium)
                        Text("Cal left", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "${-remaining}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Cal over",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
        Text(
            "${totals.eatenKcal} eaten \u00b7 ${totals.goalKcal} goal",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Compact collapsed-state stand-in for MacroRingsRow -- thin rounded
 * bars, filled-portion only (no numbers), label below each. Shown
 * crossfaded with the rings as the macro card collapses (see
 * JournalScreen's ringFraction). */
@Composable
private fun MacroBarsRow(
    fatEaten: Int, fatGoal: Int,
    proteinEaten: Int, proteinGoal: Int,
    carbsEaten: Int, carbsGoal: Int,
    fiberEaten: Int, fiberGoal: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MacroBar("Fat", fatEaten, fatGoal, MacroColors.Fat, Modifier.weight(1f))
        MacroBar("Protein", proteinEaten, proteinGoal, MacroColors.Protein, Modifier.weight(1f))
        MacroBar("Carbs", carbsEaten, carbsGoal, MacroColors.Carbs, Modifier.weight(1f))
        MacroBar("Fiber", fiberEaten, fiberGoal, MacroColors.Fiber, Modifier.weight(1f))
    }
}

@Composable
private fun MacroBar(label: String, eaten: Int, goal: Int, color: Color, modifier: Modifier = Modifier) {
    val fraction = if (goal > 0) (eaten.toFloat() / goal.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Stylized date bar: arrows in circular pill buttons, date itself in a
 * rounded-rect pill a shade darker than the white card behind it (using
 * surfaceVariant -- subtle, not a stark/high-contrast block), with a
 * small calendar glyph for decoration. Tapping the date pill opens
 * CalendarPickerDialog.
 */
@Composable
private fun DateNavRow(
    date: LocalDate,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CirclePillIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", onPrevDay)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onDateClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")),
                style = MaterialTheme.typography.titleMedium
            )
        }

        CirclePillIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", onNextDay)
    }
}

@Composable
private fun CirclePillIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

@Composable
private fun MealRow(bucket: MealBucket, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        MealIcon(bucket.mealType)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${bucket.displayName} \u00b7 ${bucket.eatenKcal} / ${bucket.goalKcal} Cal",
                style = MaterialTheme.typography.titleMedium
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(2.dp))
            if (bucket.logs.isEmpty()) {
                Text(
                    text = "Nothing logged yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                bucket.logs.forEach { log -> LoggedItemLine(log) }
            }
        }
    }
}

@Composable
private fun MealIcon(mealType: String) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MealVisuals.backgroundFor(mealType)),
        contentAlignment = Alignment.Center
    ) {
        Icon(MealVisuals.iconFor(mealType), contentDescription = null, tint = MealVisuals.iconTint)
    }
}

@Composable
private fun LoggedItemLine(log: Log) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        val name = log.itemName ?: log.recipeName ?: "Unknown"
        Text("$name (${log.kcalLogged} Cal)", style = MaterialTheme.typography.bodyMedium)
        Text(macroShortcut(log), style = MaterialTheme.typography.labelSmall)
    }
}

/** "#gF \u2022 #gP \u2022 #gC \u2022 #gFi", each number+letter colored to
 * match its ring in MacroRingsRow/MacroColors -- a quick per-item macro
 * breakdown without needing to open the item. */
private fun macroShortcut(log: Log) = buildAnnotatedString {
    withStyle(SpanStyle(color = MacroColors.Fat)) { append("${log.fatGLogged}F") }
    append(" \u2022 ")
    withStyle(SpanStyle(color = MacroColors.Protein)) { append("${log.proteinGLogged}P") }
    append(" \u2022 ")
    withStyle(SpanStyle(color = MacroColors.Carbs)) { append("${log.carbsGLogged}C") }
    append(" \u2022 ")
    withStyle(SpanStyle(color = MacroColors.Fiber)) { append("${log.fiberGLogged}Fi") }
}

/** Small helper so the fade-with-collapse reads cleanly at the call site
 * above. */
private fun Modifier.graphicsAlpha(alpha: Float): Modifier =
    this.then(Modifier.alpha(alpha.coerceIn(0f, 1f)))