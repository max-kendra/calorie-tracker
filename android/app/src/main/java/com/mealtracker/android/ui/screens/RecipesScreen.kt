package com.mealtracker.android.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.ui.components.CatalogVisuals
import com.mealtracker.android.ui.components.CropDialog
import com.mealtracker.android.ui.components.decodeBitmapWithCorrectOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Recipes tab -- replaces the old Meal Plan feature (see design
 * discussion: pre-logging real meals directly into the Journal already
 * served the "plan ahead" need in practice, so a separate draft-then-
 * commit staging area wasn't earning its keep). Browse every saved
 * recipe/meal, view its macros/ingredients (read-only here -- adding/
 * removing ingredients still happens through the meal-logging-anchored
 * RecipeInfoScreen in MealDetailScreen.kt, reached by actually logging
 * one), and view/edit step-by-step instructions or a source website
 * link. Both recipe_type values get the same treatment -- they share
 * the same underlying table, and a "meal" can be just as involved as a
 * recipe (see design discussion: pancakes are saved as a meal but still
 * benefit from having steps/a source).
 */
@Composable
fun RecipesScreen(viewModel: RecipesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    if (state.selectedRecipeId != null) {
        RecipeBrowseDetailScreen(
            viewModel = viewModel,
            recipeId = state.selectedRecipeId!!
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Recipes", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                label = { Text("Search your recipes") },
                singleLine = true,
                trailingIcon = if (state.searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.updateSearchQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }

        when {
            state.isLoadingList || state.isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.listError != null -> {
                Text(
                    state.listError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp)
                )
            }
            state.recipes.isEmpty() -> {
                Text(
                    if (state.searchQuery.isBlank()) {
                        "No recipes or meals saved yet. Save one from a meal's search sheet to see it here."
                    } else {
                        "No matches."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    state.recipes.forEach { recipe ->
                        RecipeBrowseRow(recipe = recipe, onClick = { viewModel.openRecipe(recipe.recipeId) })
                        HorizontalDivider()
                    }
                    Spacer(modifier = Modifier.padding(bottom = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun RecipeBrowseRow(recipe: Recipe, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (recipe.imagePath != null) {
                coil3.compose.AsyncImage(
                    model = com.mealtracker.android.BuildConfig.BASE_URL + recipe.imagePath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
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
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
            recipe.totalsPerServing?.let {
                Text(
                    "${it.kcal} Cal / serving",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Read-only-ingredients browse/edit-metadata detail view for a single
 * recipe/meal, reached from the Recipes tab's list. Deliberately
 * simpler than RecipeInfoScreen in MealDetailScreen.kt -- no logging
 * context (no meal/date to log into), no ingredient add/remove (that
 * stays anchored to actually logging a recipe, where quantities need a
 * concrete meal to belong to). Editable here: name, instructions,
 * source URL, servings, and the hero photo. */
@Composable
private fun RecipeBrowseDetailScreen(viewModel: RecipesViewModel, recipeId: Int) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var showImageChangeMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingCropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun clearCropState() {
        pendingCropSourceUri = null
        cropSourceBitmap = null
    }

    androidx.compose.runtime.LaunchedEffect(pendingCropSourceUri) {
        val uri = pendingCropSourceUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeBitmapWithCorrectOrientation(context, uri)
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap == null) clearCropState() else cropSourceBitmap = bitmap
    }

    fun uploadNewImage(bytes: ByteArray) {
        coroutineScope.launch {
            try {
                val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
                val scanResult = ApiClient.service.scanProductPhoto(part)
                viewModel.updateRecipeImage(scanResult.imagePath)
            } catch (e: Exception) {
                // Surfaced via imageError from the ViewModel's own catch.
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCropSourceUri = pendingCameraUri
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) pendingCropSourceUri = uri
    }

    fun launchCamera() {
        val file = java.io.File(context.cacheDir, "recipe_photo_${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val recipe = state.recipeDetail

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clickable { showImageChangeMenu = true }
            ) {
                if (recipe?.imagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + recipe.imagePath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(CatalogVisuals.backgroundFor(recipe?.recipeType ?: "recipe")),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            CatalogVisuals.iconFor(recipe?.recipeType ?: "recipe"),
                            contentDescription = null,
                            tint = CatalogVisuals.iconTint(),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                if (state.isUploadingImage) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                IconButton(
                    onClick = { viewModel.dismissDetail() },
                    modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                if (recipe != null && !state.isEditing) {
                    IconButton(
                        onClick = { viewModel.startEditing() },
                        modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape).align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White)
                    }
                }
            }
            if (state.imageError != null) {
                Text(state.imageError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            when {
                state.isLoadingDetail -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                state.detailError != null -> {
                    Text(state.detailError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
                }
                recipe != null -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        if (state.isEditing) {
                            OutlinedTextField(
                                value = state.editName,
                                onValueChange = { viewModel.updateEditName(it) },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            if (recipe.recipeType != "meal") {
                                OutlinedTextField(
                                    value = state.editServings,
                                    onValueChange = { viewModel.updateEditServings(it) },
                                    label = { Text("Servings") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.padding(top = 8.dp))
                            }
                            OutlinedTextField(
                                value = state.editSourceUrl,
                                onValueChange = { viewModel.updateEditSourceUrl(it) },
                                label = { Text("Source website (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            OutlinedTextField(
                                value = state.editInstructions,
                                onValueChange = { viewModel.updateEditInstructions(it) },
                                label = { Text("Instructions (optional)") },
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                            if (state.saveError != null) {
                                Text(state.saveError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                            }
                            Spacer(modifier = Modifier.padding(top = 12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.saveEdits() }, enabled = !state.isSaving, modifier = Modifier.weight(1f)) {
                                    Text(if (state.isSaving) "Saving..." else "Save")
                                }
                                TextButton(onClick = { viewModel.cancelEditing() }, enabled = !state.isSaving) { Text("Cancel") }
                            }
                            Spacer(modifier = Modifier.padding(top = 12.dp))
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Text("Delete this ${if (recipe.recipeType == "meal") "meal" else "recipe"}", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text(recipe.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (recipe.recipeType == "meal") {
                                    "${recipe.totalsPerServing.kcal} Cal"
                                } else {
                                    "${recipe.totalsPerServing.kcal} Cal / serving \u00b7 ${recipe.servings} servings total"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.padding(top = 16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                RecipeBrowseMacro("Protein", recipe.totalsPerServing.proteinG)
                                RecipeBrowseMacro("Fat", recipe.totalsPerServing.fatG)
                                RecipeBrowseMacro("Carbs", recipe.totalsPerServing.carbsG)
                                RecipeBrowseMacro("Fiber", recipe.totalsPerServing.fiberG)
                            }

                            if (recipe.sourceUrl != null) {
                                Spacer(modifier = Modifier.padding(top = 20.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(recipe.sourceUrl) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.padding(start = 6.dp))
                                    Text("View source", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            if (!recipe.instructions.isNullOrBlank()) {
                                Spacer(modifier = Modifier.padding(top = 20.dp))
                                Text("Instructions", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.padding(top = 4.dp))
                                Text(recipe.instructions, style = MaterialTheme.typography.bodyMedium)
                            }

                            Spacer(modifier = Modifier.padding(top = 20.dp))
                            Text("Ingredients", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.padding(top = 4.dp))
                            if (recipe.ingredients.isEmpty()) {
                                Text("No ingredients listed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                recipe.ingredients.forEach { ingredient ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(ingredient.itemName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${ingredient.quantity.toDoubleOrNull()?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: ingredient.quantity}" +
                                                (ingredient.servingSizeName?.let { " $it" } ?: "g"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.padding(bottom = 20.dp))
                        }
                    }
                }
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

    if (showImageChangeMenu) {
        AlertDialog(
            onDismissRequest = { showImageChangeMenu = false },
            title = { Text("Change photo") },
            text = { Text("Take a photo or pick one from your gallery.") },
            confirmButton = {
                TextButton(onClick = { showImageChangeMenu = false; launchCamera() }) { Text("Take Photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageChangeMenu = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Choose from Gallery") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this ${if (recipe?.recipeType == "meal") "meal" else "recipe"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.deleteRecipe() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecipeBrowseMacro(label: String, grams: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${grams}g", style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}