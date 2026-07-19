package com.mealtracker.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.ui.components.BarcodeScannerWithControls
import com.mealtracker.android.ui.components.CropDialog
import com.mealtracker.android.ui.components.LiveLabelCaptureView
import com.mealtracker.android.ui.components.decodeBarcodeFromUri
import com.mealtracker.android.ui.components.decodeBitmapWithCorrectOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// How long the live barcode scanner runs before offering a manual-entry
// fallback -- see design doc: real barcodes do occasionally get worn or
// printed badly, so this isn't a general "manual entry" escape hatch,
// just a narrow fallback for when scanning genuinely can't read a
// physically-present barcode.
// Was 8s -- bumped up since that felt naggy/premature for a barcode
// that just takes a little longer to line up (see design discussion).
private const val BARCODE_TIMEOUT_MS = 20000L

@Composable
fun AddItemScreen(
    viewModel: AddItemViewModel = viewModel(),
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
    // Tapping the matched-item toast -- bubbles up to MealDetailScreen,
    // which owns the actual rich item info page (ItemLogPageDialog).
    // AddItemViewModel has no access to that (separate ViewModel/
    // screen), so this can't be handled locally the way onDone/onBack
    // are.
    onOpenItemDetail: (com.mealtracker.android.network.models.Item) -> Unit = {},
    // Wording for the SAVED screen's secondary action -- defaults match
    // the original meal-logging framing. Recipe ingredient picking (see
    // CreateRecipeIngredientsScreen) passes its own wording since
    // "add this item to your meal" doesn't apply there. Default action
    // is "View Item" rather than "Add Item" -- even someone who does
    // want to add it will likely want to adjust the quantity first (see
    // design discussion), so the secondary action always opens the
    // quantity picker rather than logging a flat default quantity
    // directly.
    savedScreenPromptText: String = "Want to review it before adding to your meal?",
    savedScreenActionLabel: String = "View Item",
    // When non-null, the SAVED screen's secondary action calls THIS with
    // the created/matched item instead of onOpenItemDetail -- lets the
    // entire scan/search/create flow be reused for contexts that aren't
    // meal-logging at all (currently: adding a scanned item as a recipe
    // ingredient, which opens ITS OWN quantity picker via
    // addIngredientFromBarcodeFlow). Leave null (the default) to route
    // through onOpenItemDetail instead, which opens MealDetailScreen's
    // own quantity picker (see MealDetailScreen's onOpenItemDetail
    // wiring) -- same "view and adjust" behavior, just a different
    // picker depending on context.
    onUseCreatedItem: ((com.mealtracker.android.network.models.Item) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Nutrition-label photos (captured OR gallery-picked) go through a
    // crop step before OCR -- tightly cropping out ingredient lists/
    // marketing text/other noise should meaningfully help OCR accuracy
    // on real package photos. No fixed aspect ratio (freestyle crop),
    // since labels vary a lot in shape. Same crop step applies to the
    // product photo.
    //
    // Uses our own CropDialog (see ui/components/CropDialog.kt) rather
    // than a third-party cropper -- see that file's doc comment for why.
    // Flow: startCrop() stashes the source Uri + a completion callback;
    // a LaunchedEffect below decodes it to a Bitmap off the main thread;
    // once decoded, CropDialog is shown; its onCropped/onCancel results
    // feed back into the stashed callback and clear this state.
    var pendingCropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var cropSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var onCropComplete by remember { mutableStateOf<((ByteArray) -> Unit)?>(null) }

    fun clearCropState() {
        pendingCropSourceUri = null
        cropSourceBitmap = null
        onCropComplete = null
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
            // Couldn't decode the image at all -- clear state so the user
            // just lands back on the capture phase and can retry, same as
            // any other crop-failure path.
            clearCropState()
        } else {
            cropSourceBitmap = bitmap
        }
    }

    fun startCrop(sourceUri: Uri, onCropped: (ByteArray) -> Unit) {
        onCropComplete = onCropped
        pendingCropSourceUri = sourceUri
    }

    fun writeBytesToCacheAndGetUri(bytes: ByteArray): Uri {
        val file = java.io.File(context.cacheDir, "captured_${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }

    // Gallery pickers -- modern Android Photo Picker, no storage
    // permission needed at all. One shared launcher per step, since each
    // needs a different follow-up action.
    val galleryPickerForBarcode = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val barcode = decodeBarcodeFromUri(context, uri)
                viewModel.onGalleryBarcodeResult(barcode)
            }
        }
    }

    val galleryPickerForProductPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            startCrop(uri) { viewModel.scanProductPhoto(it) }
        }
    }

    val galleryPickerForLabel = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // Content Uris from the picker can be used directly as the
        // cropper's source, no need to copy to cache first.
        if (uri != null) {
            startCrop(uri) { viewModel.scanLabel(it) }
        }
    }

    // For ITEM_FORM's own "Add Photo" -- items that skipped the usual
    // photo-capture steps entirely (USDA imports currently) had no way
    // to attach one at all, see design discussion.
    val galleryPickerForFormPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            startCrop(uri) { viewModel.attachPhotoToForm(it) }
        }
    }

    // Camera option for the same button -- this used to only offer
    // gallery picking, per design discussion. Same TakePicture()+
    // FileProvider pattern as MealDetailScreen's ItemLogPageDialog (see
    // that file's launchCamera() -- file.createNewFile() matters there,
    // some camera apps silently fail to write into a content:// Uri
    // otherwise).
    var pendingFormPhotoCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncherForFormPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingFormPhotoCameraUri
        if (success && uri != null) {
            startCrop(uri) { viewModel.attachPhotoToForm(it) }
        }
    }
    fun launchCameraForFormPhoto() {
        val file = java.io.File(context.cacheDir, "item_photo_${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        pendingFormPhotoCameraUri = uri
        cameraLauncherForFormPhoto.launch(uri)
    }
    var showFormPhotoMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (state.phase == AddItemPhase.SAVED) onDone() else onBack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Add Item", style = MaterialTheme.typography.titleLarge)
        }

        if (state.showManualEntryPrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissManualEntryPrompt() },
                title = { Text("No barcode detected") },
                text = { Text("Would you like to enter the barcode number manually instead?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.proceedToManualBarcodeEntry() }) {
                        Text("Enter Manually")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissManualEntryPrompt() }) {
                        Text("Keep Scanning")
                    }
                }
            )
        }

        if (state.showOcrFailedDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissOcrFailedDialog() },
                title = { Text("Couldn't read that label") },
                text = { Text("Would you like to take a new picture, or enter the nutrition info yourself?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissOcrFailedDialog() }) {
                        Text("Take New Picture")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.proceedToManualFormFromOcrFailure() }) {
                        Text("Enter Manually")
                    }
                }
            )
        }

        // CropDialog itself now renders as a Box sibling below (outside
        // this Column), so it overlays full-screen instead of being laid
        // out as normal Column flow content -- see CropDialog's doc
        // comment for why it's no longer its own separate Dialog window.

        if (!hasCameraPermission &&
            (state.phase == AddItemPhase.SCAN_BARCODE ||
                state.phase == AddItemPhase.CAPTURE_PRODUCT_PHOTO ||
                state.phase == AddItemPhase.CAPTURE_LABEL)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission is needed to add items this way.")
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
            return
        }

        // Wrapped in its own local composable function -- NOT just
        // inlined here -- to work around a Kotlin K2 compiler crash
        // ("PostponedLambdaExitNode not initialized - traversing nodes
        // in wrong order?", a FIR flow-analysis internal error). This
        // when expression has grown to 12+ branches, many containing
        // their own lambda arguments (LaunchedEffect blocks, onClick
        // handlers, etc.) -- that's a known trigger shape for this K2
        // bug category: the frontend's control-flow analysis can choke
        // on a single large expression with many nested lambdas. Giving
        // it its own function body isolates that analysis without
        // needing to pass every enclosing launcher/lambda (there are
        // many: galleryPickerForProductPhoto, startCrop, etc.) as
        // explicit parameters -- a LOCAL function still captures all of
        // those by closure exactly as before; only the when expression
        // itself is isolated, nothing about its logic changed.
        @Composable
        fun PhaseContent() {
            when (state.phase) {
            AddItemPhase.SCAN_BARCODE -> {
                LaunchedEffect(state.phase, state.scanError) {
                    delay(BARCODE_TIMEOUT_MS)
                    viewModel.onBarcodeTimeout()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    BarcodeScannerWithControls(
                        scanError = state.scanError,
                        onBarcodeDetected = { viewModel.onLiveBarcodeDetected(it) },
                        onPickFromGallery = {
                            galleryPickerForBarcode.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    // Matched item shown as a toast OVER the still-live
                    // camera instead of navigating away -- see design
                    // discussion ("the barcode flow was good exactly the
                    // way it was", just wanted the match result to be
                    // less of an interruption).
                    state.matchedItemToast?.let { item ->
                        MatchedItemToast(
                            item = item,
                            onDismiss = { viewModel.dismissMatchedItemToast() },
                            onTapDetail = {
                                viewModel.dismissMatchedItemToast()
                                onOpenItemDetail(item)
                            },
                            onAdd = { viewModel.useMatchedItem() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
            AddItemPhase.BARCODE_LOOKUP -> LoadingContent()
            AddItemPhase.BARCODE_RESULT -> BarcodeResultContent(
                barcode = state.scannedBarcode,
                decoderUsed = state.decoderUsed,
                matchedItem = state.matchedItem,
                onUseExisting = { viewModel.useMatchedItem() },
                onRetry = { viewModel.retryBarcodeScan() }
            )
            AddItemPhase.MANUAL_BARCODE_ENTRY -> ManualBarcodeEntryContent(
                value = state.manualBarcodeInput,
                onValueChange = { viewModel.updateManualBarcodeInput(it) },
                onSubmit = { viewModel.submitManualBarcode() }
            )
            AddItemPhase.USDA_SEARCH -> UsdaSearchContent(
                query = state.usdaQuery,
                results = state.usdaResults,
                isSearching = state.isSearchingUsda,
                error = state.usdaError,
                onQueryChange = { viewModel.updateUsdaQuery(it) },
                onResultClick = { fdcId -> viewModel.selectUsdaFood(fdcId) }
            )
            AddItemPhase.ENTER_NAME_BRAND -> EnterNameBrandContent(
                name = state.name,
                brand = state.brand,
                onNameChange = { viewModel.updateName(it) },
                onBrandChange = { viewModel.updateBrand(it) },
                onContinue = { viewModel.confirmNameBrand() }
            )
            AddItemPhase.CAPTURE_PRODUCT_PHOTO -> CaptureLabelContent(
                instructionText = "Frame the product package",
                scanError = state.scanError,
                onImageCaptured = { bytes ->
                    startCrop(writeBytesToCacheAndGetUri(bytes)) { viewModel.scanProductPhoto(it) }
                },
                onPickFromGallery = {
                    galleryPickerForProductPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onSkip = { viewModel.skipProductPhoto() },
                skipLabel = "Skip"
            )
            AddItemPhase.PROCESSING_PRODUCT_PHOTO -> LoadingContent()
            AddItemPhase.CAPTURE_LABEL -> CaptureLabelContent(
                instructionText = "Frame the nutrition label",
                scanError = state.scanError,
                onImageCaptured = { bytes ->
                    startCrop(writeBytesToCacheAndGetUri(bytes)) { viewModel.scanLabel(it) }
                },
                onPickFromGallery = {
                    galleryPickerForLabel.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onSkip = { viewModel.proceedToManualFormFromOcrFailure() },
                skipLabel = "Enter Manually"
            )
            AddItemPhase.PROCESSING_LABEL -> LoadingContent()
            AddItemPhase.ITEM_FORM, AddItemPhase.SAVING -> ItemFormContent(
                state = state,
                isSaving = state.phase == AddItemPhase.SAVING,
                viewModel = viewModel,
                onAddPhoto = { showFormPhotoMenu = true }
            )
            AddItemPhase.SAVED -> SavedContent(
                itemName = state.createdItem?.name ?: "",
                isLoggingToMeal = state.isLoggingToMeal,
                promptText = savedScreenPromptText,
                secondaryActionLabel = savedScreenActionLabel,
                onAddItem = {
                    val item = state.createdItem
                    if (item != null) {
                        if (onUseCreatedItem != null) {
                            onUseCreatedItem(item)
                        } else {
                            onOpenItemDetail(item)
                        }
                    }
                    onDone()
                },
                onDone = onDone
            )
        }
        }
        PhaseContent()
    }

    // Rendered as a Box sibling AFTER the main Column above, so it
    // overlays full-screen on top of whatever phase is showing instead
    // of being laid out as normal Column flow content (which would push
    // things down / not actually cover the camera preview etc.). See
    // CropDialog's own doc comment for why it's a plain composable now,
    // not a separate Dialog window.
    val bitmapToCrop = cropSourceBitmap
    if (bitmapToCrop != null) {
        CropDialog(
            sourceBitmap = bitmapToCrop,
            onCropped = { cropped ->
                val stream = java.io.ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val callback = onCropComplete
                clearCropState()
                callback?.invoke(stream.toByteArray())
            },
            onCancel = { clearCropState() }
        )
    }

    if (showFormPhotoMenu) {
        // Used to jump straight to the gallery picker with no camera
        // option at all -- per design discussion, offering both here
        // matches every other photo-capture point in this flow.
        AlertDialog(
            onDismissRequest = { showFormPhotoMenu = false },
            title = { Text("Add photo") },
            text = { Text("Take a photo or pick one from your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    showFormPhotoMenu = false
                    launchCameraForFormPhoto()
                }) { Text("Take Photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFormPhotoMenu = false
                    galleryPickerForFormPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text("Choose from Gallery") }
            }
        )
    }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Reached on a no-match barcode, before any photo -- asks for name and
 * brand directly instead of guessing them from OCR on the product
 * photo. See AddItemPhase.ENTER_NAME_BRAND's doc comment for why. */
@Composable
private fun EnterNameBrandContent(
    name: String,
    brand: String,
    onNameChange: (String) -> Unit,
    onBrandChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("What is this item?", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            "You'll still be able to review the nutrition info before saving.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = brand,
            onValueChange = onBrandChange,
            label = { Text("Brand (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onContinue, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

@Composable
private fun ManualBarcodeEntryContent(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter the barcode number", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Barcode") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

/** Cute little toast shown over the still-live camera when a scanned
 * barcode matches an existing item (see design discussion) -- tapping
 * the card body opens BARCODE_RESULT for more detail, tapping Add logs
 * it directly, X dismisses and lets scanning continue. */
@Composable
private fun MatchedItemToast(
    item: com.mealtracker.android.network.models.Item,
    onDismiss: () -> Unit,
    onTapDetail: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(onClick = onTapDetail)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(com.mealtracker.android.ui.components.CatalogVisuals.backgroundFor(item.type)),
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
                        com.mealtracker.android.ui.components.CatalogVisuals.iconFor(item.type),
                        contentDescription = null,
                        tint = com.mealtracker.android.ui.components.CatalogVisuals.iconTint(),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                if (item.brand != null) {
                    Text(
                        item.brand,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.kcal100g != null) {
                    Text(
                        "${item.kcal100g} Cal / 100g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/** Raw-ingredient lookup against USDA FoodData Central -- picking a
 * result pre-fills the item form (see AddItemViewModel.selectUsdaFood),
 * same review-before-save pattern as OCR and barcode matching. */
@Composable
private fun UsdaSearchContent(
    query: String,
    results: List<com.mealtracker.android.network.models.UsdaFoodSummary>,
    isSearching: Boolean,
    error: String?,
    onQueryChange: (String) -> Unit,
    onResultClick: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Search for a raw ingredient", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("e.g. \"banana, raw\"") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        when {
            isSearching -> {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            results.isEmpty() -> {
                Text(
                    if (query.isBlank()) "Start typing to search USDA" else "No matches",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    results.forEach { food ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultClick(food.fdcId) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(food.description, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = listOfNotNull(
                                food.brandOwner,
                                food.macros.kcal100g?.let { "$it kcal/100g" }
                            ).joinToString(" \u00b7 ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BarcodeResultContent(
    barcode: String?,
    decoderUsed: String?,
    matchedItem: com.mealtracker.android.network.models.Item?,
    onUseExisting: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // IMPORTANT: always show the decoded digits for the user to
        // visually confirm against the physical package -- real-world
        // testing found decoders can return a wrong value that still
        // looks structurally valid (see AddItemViewModel/Models.kt docs).
        Text("Scanned barcode:", style = MaterialTheme.typography.bodyMedium)
        Text(barcode ?: "", style = MaterialTheme.typography.headlineSmall)
        if (decoderUsed != null) {
            Text(
                "(via $decoderUsed)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Double-check this matches the number on the package before continuing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        // Only ever reached with a match now -- a barcode with no match
        // auto-continues straight into CAPTURE_PRODUCT_PHOTO instead of
        // stopping here (see AddItemViewModel.lookUpBarcode's doc
        // comment), so there's no "no existing item" branch to show.
        Text("Found an existing item: ${matchedItem?.name}", style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Button(onClick = onUseExisting, modifier = Modifier.fillMaxWidth()) {
            Text("Use This Item")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Again")
        }
    }
}

@Composable
private fun CaptureLabelContent(
    instructionText: String,
    scanError: String?,
    onImageCaptured: (ByteArray) -> Unit,
    onPickFromGallery: () -> Unit,
    onSkip: (() -> Unit)? = null,
    skipLabel: String = "Skip"
) {
    val context = LocalContext.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LiveLabelCaptureView(
            onImageCaptureReady = { imageCapture = it },
            onCameraReady = { camera = it },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                instructionText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            if (scanError != null) {
                Text(scanError, color = MaterialTheme.colorScheme.error)
            }
        }

        IconButton(
            onClick = {
                isFlashOn = !isFlashOn
                camera?.cameraControl?.enableTorch(isFlashOn)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) {
            Icon(
                if (isFlashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "Toggle flash",
                tint = Color.White
            )
        }

        // onSkip button itself now renders bottom-center, grouped with
        // Capture/gallery below (see that Row's doc comment for why it
        // moved off the top of the screen).

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (onSkip != null) 88.dp else 32.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPickFromGallery) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "Pick from gallery instead",
                    tint = Color.White
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(24.dp))
            Button(onClick = {
                val capture = imageCapture ?: return@Button
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            onImageCaptured(bytes)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            // Not surfaced to the UI -- a failed capture just
                            // means the user taps the button again; not worth
                            // a separate error-plumbing path for a rare
                            // hardware-level failure.
                        }
                    }
                )
            }) {
                Text("Capture")
            }
        }

        if (onSkip != null) {
            // Grouped with the bottom controls now, not pinned top-start
            // -- it used to sit right under/against the centered
            // instruction text at the top of the screen, close enough
            // that they visually ran together (see design discussion:
            // "almost merges together with 'Frame the nutrition
            // label'"). Bottom-center, below Capture/gallery, keeps it
            // near the other actions instead.
            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                Text(skipLabel, color = Color.White)
            }
        }
    }
}

@Composable
private fun ItemFormContent(
    state: AddItemUiState,
    isSaving: Boolean,
    viewModel: AddItemViewModel,
    onAddPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Lets the user attach/replace a photo directly here -- items
        // that skip the usual capture steps (USDA imports currently)
        // otherwise had no way to add one at all. Same crop pipeline as
        // everywhere else (see AddItemScreen's galleryPickerForFormPhoto).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAddPhoto),
                contentAlignment = Alignment.Center
            ) {
                if (state.productImagePath != null) {
                    coil3.compose.AsyncImage(
                        model = com.mealtracker.android.BuildConfig.BASE_URL + state.productImagePath,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = "Add photo")
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 12.dp))
            TextButton(onClick = onAddPhoto) {
                Text(if (state.productImagePath != null) "Change Photo" else "Add Photo")
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))

        if (state.ocrWasUsed) {
            if (!state.ocrPer100gConfirmed) {
                Text(
                    "\u26a0\ufe0f Couldn't confirm these values are per 100g -- " +
                        "double-check against the label and adjust \"Values are per\" below if needed.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.ocrDetectedLanguage != null) {
                Text(
                    "Detected label language: ${state.ocrDetectedLanguage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Review the extracted values below -- OCR isn't perfect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Retaking used to only be reachable via the OCR-failure
            // dialog, which only shows on a COMPLETE failure -- a
            // partial/wrong-but-nonzero result had no way back to try
            // again short of restarting the whole flow (see design
            // discussion). Available here any time OCR was used at all.
            TextButton(onClick = { viewModel.retakeLabelPhoto() }) {
                Text("Retake Label Photo")
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        // Brand/barcode don't apply to raw ingredients (a banana isn't
        // "branded" and doesn't have its own barcode) -- hidden rather
        // than just left empty, per design discussion.
        if (state.itemType != "ingredient") {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            OutlinedTextField(
                value = state.brand,
                onValueChange = viewModel::updateBrand,
                label = { Text("Brand (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
            OutlinedTextField(
                value = state.barcode,
                onValueChange = viewModel::updateBarcode,
                label = { Text("Barcode") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.itemType == "product",
                onClick = { viewModel.updateItemType("product") },
                label = { Text("Product") }
            )
            FilterChip(
                selected = state.itemType == "ingredient",
                onClick = { viewModel.updateItemType("ingredient") },
                label = { Text("Ingredient") }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        // Editable now -- not every label reports macros per 100g (see
        // design discussion), so the amount these values are entered
        // for needs to be something the user can correct, not a fixed
        // "Per 100g" label. Converted to true per-100g at save time
        // (see AddItemViewModel.saveItem) regardless of what's typed
        // here.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Values are per", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
            OutlinedTextField(
                value = state.perAmountG,
                onValueChange = viewModel::updatePerAmountG,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.width(90.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
            Text("g", style = MaterialTheme.typography.titleSmall)
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

        NumberField("Calories (kcal)", state.kcal100g, viewModel::updateKcal)
        NumberField("Protein (g)", state.protein100g, viewModel::updateProtein)
        NumberField("Fat (g)", state.fat100g, viewModel::updateFat)
        NumberField("Saturated fat (g)", state.saturatedFat100g, viewModel::updateSaturatedFat)
        NumberField("Carbs (g)", state.carbs100g, viewModel::updateCarbs)
        NumberField("Sugar (g)", state.sugar100g, viewModel::updateSugar)
        NumberField("Fiber (g)", state.fiber100g, viewModel::updateFiber)
        NumberField("Salt (g)", state.saltG100g, viewModel::updateSalt, isLast = true)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))

        Button(
            onClick = { viewModel.saveItem() },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSaving) "Saving..." else "Save Item")
        }
        if (state.saveError != null) {
            Text(
                state.saveError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, isLast: Boolean = false) {
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
private fun SavedContent(
    itemName: String,
    isLoggingToMeal: Boolean,
    promptText: String,
    secondaryActionLabel: String,
    onAddItem: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("\u2705 Saved", style = MaterialTheme.typography.headlineMedium)
        Text(itemName, style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        Text(
            promptText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
        // "Add Another Item" is gone -- saving/matching an item no
        // longer auto-logs it to the meal either (see design
        // discussion), so the only decision left here is whether THIS
        // item should also get logged, framed as a low-key, easy-to-
        // ignore option rather than an equally-weighted second button.
        // Done is the one obvious, big action; the secondary action is a
        // small, clearly-secondary text underneath it. Same reasoning
        // applies verbatim to the recipe-ingredient reuse of this screen
        // (see AddItemScreen's onUseCreatedItem doc comment) -- just
        // with different wording for what "add" means there.
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        TextButton(onClick = onAddItem, enabled = !isLoggingToMeal) {
            Text(if (isLoggingToMeal) "Adding..." else secondaryActionLabel)
        }
    }
}