package com.mealtracker.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.ui.components.LiveBarcodeScannerView
import com.mealtracker.android.ui.components.LiveLabelCaptureView
import com.mealtracker.android.ui.components.decodeBarcodeFromUri
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How long the live barcode scanner runs before offering a manual-entry
// fallback -- see design doc: real barcodes do occasionally get worn or
// printed badly, so this isn't a general "manual entry" escape hatch,
// just a narrow fallback for when scanning genuinely can't read a
// physically-present barcode.
private const val BARCODE_TIMEOUT_MS = 8000L

@Composable
fun AddItemScreen(
    viewModel: AddItemViewModel = viewModel(),
    onBack: () -> Unit = {},
    onDone: () -> Unit = {}
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
    // marketing text/other noise should meaningfully help Tesseract's
    // accuracy on real package photos. No fixed aspect ratio (freestyle
    // crop), since labels vary a lot in shape.
    val ucropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val croppedUri = UCrop.getOutput(result.data!!)
            if (croppedUri != null) {
                context.contentResolver.openInputStream(croppedUri)?.use { stream ->
                    viewModel.scanLabel(stream.readBytes())
                }
            }
        }
        // Cancelled or errored -- user just stays on CAPTURE_LABEL and
        // can retake/repick; nothing else to do here.
    }

    fun startCrop(sourceUri: Uri) {
        val destFile = java.io.File(context.cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        val destUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", destFile
        )
        val intent = UCrop.of(sourceUri, destUri).getIntent(context)
        ucropLauncher.launch(intent)
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

    val galleryPickerForLabel = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // Content Uris from the picker can be used directly as UCrop's
        // source, no need to copy to cache first.
        if (uri != null) {
            startCrop(uri)
        }
    }

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

        if (!hasCameraPermission &&
            (state.phase == AddItemPhase.SCAN_BARCODE || state.phase == AddItemPhase.CAPTURE_LABEL)
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

        when (state.phase) {
            AddItemPhase.SCAN_BARCODE -> {
                LaunchedEffect(state.phase, state.scanError) {
                    delay(BARCODE_TIMEOUT_MS)
                    viewModel.onBarcodeTimeout()
                }
                BarcodeScanContent(
                    scanError = state.scanError,
                    onBarcodeDetected = { viewModel.onLiveBarcodeDetected(it) },
                    onPickFromGallery = {
                        galleryPickerForBarcode.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }
            AddItemPhase.BARCODE_LOOKUP -> LoadingContent()
            AddItemPhase.BARCODE_RESULT -> BarcodeResultContent(
                barcode = state.scannedBarcode,
                decoderUsed = state.decoderUsed,
                matchedItem = state.matchedItem,
                onUseExisting = onDone,
                onContinueToLabel = { viewModel.proceedToCaptureLabel() },
                onRetry = { viewModel.retryBarcodeScan() }
            )
            AddItemPhase.MANUAL_BARCODE_ENTRY -> ManualBarcodeEntryContent(
                value = state.manualBarcodeInput,
                onValueChange = { viewModel.updateManualBarcodeInput(it) },
                onSubmit = { viewModel.submitManualBarcode() }
            )
            AddItemPhase.CAPTURE_LABEL -> CaptureLabelContent(
                scanError = state.scanError,
                onImageCaptured = { bytes -> startCrop(writeBytesToCacheAndGetUri(bytes)) },
                onPickFromGallery = {
                    galleryPickerForLabel.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
            AddItemPhase.PROCESSING_LABEL -> LoadingContent()
            AddItemPhase.ITEM_FORM, AddItemPhase.SAVING -> ItemFormContent(
                state = state,
                isSaving = state.phase == AddItemPhase.SAVING,
                viewModel = viewModel
            )
            AddItemPhase.SAVED -> SavedContent(
                itemName = state.createdItem?.name ?: "",
                onDone = onDone
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

@Composable
private fun BarcodeScanContent(
    scanError: String?,
    onBarcodeDetected: (String) -> Unit,
    onPickFromGallery: () -> Unit
) {
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LiveBarcodeScannerView(
            onBarcodeDetected = onBarcodeDetected,
            onCameraReady = { camera = it },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Point the camera at a barcode",
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
        IconButton(
            onClick = onPickFromGallery,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = "Pick from gallery instead",
                tint = Color.White,
                modifier = Modifier.padding(4.dp)
            )
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

@Composable
private fun BarcodeResultContent(
    barcode: String?,
    decoderUsed: String?,
    matchedItem: com.mealtracker.android.network.models.Item?,
    onUseExisting: () -> Unit,
    onContinueToLabel: () -> Unit,
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

        if (matchedItem != null) {
            Text("Found an existing item: ${matchedItem.name}", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onUseExisting, modifier = Modifier.fillMaxWidth()) {
                Text("Use This Item")
            }
        } else {
            Text(
                "No existing item has this barcode.",
                style = MaterialTheme.typography.bodyMedium
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onContinueToLabel, modifier = Modifier.fillMaxWidth()) {
                Text("Continue \u2192 Scan Nutrition Label")
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Again")
        }
    }
}

@Composable
private fun CaptureLabelContent(
    scanError: String?,
    onImageCaptured: (ByteArray) -> Unit,
    onPickFromGallery: () -> Unit
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
                "Frame the nutrition label",
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

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
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
    }
}

@Composable
private fun ItemFormContent(state: AddItemUiState, isSaving: Boolean, viewModel: AddItemViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (state.ocrWasUsed) {
            if (!state.ocrPer100gConfirmed) {
                Text(
                    "\u26a0\ufe0f Couldn't confirm these values are per 100g -- " +
                        "double-check against the label (they might be per-serving).",
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
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
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
        Text("Per 100g", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))

        NumberField("Calories (kcal)", state.kcal100g, viewModel::updateKcal)
        NumberField("Protein (g)", state.protein100g, viewModel::updateProtein)
        NumberField("Carbs (g)", state.carbs100g, viewModel::updateCarbs)
        NumberField("Fat (g)", state.fat100g, viewModel::updateFat)
        NumberField("Fiber (g)", state.fiber100g, viewModel::updateFiber)
        NumberField("Sugar (g)", state.sugar100g, viewModel::updateSugar)
        NumberField("Saturated fat (g)", state.saturatedFat100g, viewModel::updateSaturatedFat)
        NumberField("Sodium (mg)", state.sodiumMg100g, viewModel::updateSodium)

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
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
private fun SavedContent(itemName: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("\u2705 Saved", style = MaterialTheme.typography.headlineMedium)
        Text(itemName, style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
