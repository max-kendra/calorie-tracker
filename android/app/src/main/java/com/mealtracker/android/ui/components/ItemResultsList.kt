package com.mealtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.ui.screens.LoggedAmount
import kotlin.math.roundToInt

/** "142 Cal, 100g" preview of what tapping "+" would actually log for
 * this item right now, based on the last quantity/serving used for it
 * -- prefers this session's own remembered amount (freshest), then the
 * item's own persisted last-logged fields (survives across meals/days/
 * sessions - see design discussion: "we're saving the last logged
 * quantity/serving, but in the item list, the quantity is always 100g"
 * -- this fell back straight to a flat 100g whenever remembered was
 * null, ignoring the persisted fields entirely, even though the item's
 * own info page read them correctly), and only then a flat 100g
 * default if this item has never been logged at all. Returns null if
 * there's not enough info to compute a preview (no kcal_100g on the
 * item, or a remembered serving that's since been deleted). Shared
 * across every place that lists items to add (meal search, recipe
 * ingredient search) so the preview logic and its edge cases only
 * exist once. */
fun quickAddPreview(item: Item, remembered: LoggedAmount?): String? {
    val quantity: Double
    val servingSizeId: Int?
    if (remembered != null) {
        quantity = remembered.quantity
        servingSizeId = remembered.servingSizeId
    } else {
        quantity = item.lastLoggedQuantity?.toDoubleOrNull() ?: 100.0
        servingSizeId = item.lastLoggedServingSizeId
    }
    val grams = if (servingSizeId != null) {
        val serving = item.servingSizes.find { it.id == servingSizeId } ?: return null
        quantity * (serving.weightG.toDoubleOrNull() ?: return null)
    } else {
        quantity
    }
    val kcal = item.kcal100g?.toDoubleOrNull()?.times(grams / 100.0) ?: return null
    return "${kcal.roundToInt()} Cal, ${"%.0f".format(grams)}g"
}

/**
 * Results list backing item search everywhere it appears (meal search,
 * recipe ingredient search) -- bounded height + its own scroll so a
 * long list doesn't fight an enclosing drag gesture (e.g. the meal
 * sheet). Shows each item's image, brand, and a live preview of what
 * tapping "+" would log right now (see quickAddPreview). Tapping the
 * row itself opens whatever quantity/serving picker the caller wants
 * (onItemClick) -- this component only renders the list, it has no
 * opinion on what happens after a tap.
 */
@Composable
fun ItemResultsList(
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
            // Only blanks to a spinner on a genuine first load (no
            // items yet) -- a refresh that already has items to show
            // (e.g. after logging one, or a recipe-ingredient edit)
            // used to flash the whole list away and back for every
            // single such refresh, since isLoading was checked
            // unconditionally here regardless of whether stale-but-
            // still-valid items were already on screen (see design
            // discussion: "the entire list reloads each time and
            // disappears for a split second").
            isLoading && items.isEmpty() -> {
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
                            // Tapping the ROW opens whatever quantity/
                            // serving picker the caller wants -- the
                            // separate "+" button below is still the
                            // flat-quantity quick-add shortcut, kept for
                            // when you just want the default fast
                            // without picking anything.
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