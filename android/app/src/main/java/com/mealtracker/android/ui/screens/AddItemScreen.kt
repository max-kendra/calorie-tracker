package com.mealtracker.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mealtracker.android.ui.components.LiveBarcodeScannerView
import java.io.ByteArrayOutputStream

// Barcode scanning is now live/on-device (see LiveBarcodeScannerView) --
// only label OCR still needs an actual photo captured and uploaded,
// since that has to go to the backend's Tesseract pipeline.
private enum class PendingScan { NONE, LIVE_BARCODE, LABEL_PHOTO }

@Composable
fun AddItemScreen(
    viewModel: AddItemViewModel = viewModel(),
    onBack: () -> Unit = {},
    onDone: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var pendingScan by remember { mutableStateOf(PendingScan.NONE) }

    fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            viewModel.scanLabel(bitmapToJpegBytes(bitmap))
        }
        pendingScan = PendingScan.NONE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingScan) {
                PendingScan.LIVE_BARCODE -> {
                    viewModel.startLiveBarcodeScan()
                    pendingScan = PendingScan.NONE
                }
                PendingScan.LABEL_PHOTO -> cameraLauncher.launch(null)
                PendingScan.NONE -> {}
            }
        } else {
            pendingScan = PendingScan.NONE
        }
    }

    fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    fun startBarcodeScan() {
        if (hasCameraPermission()) {
            viewModel.startLiveBarcodeScan()
        } else {
            pendingScan = PendingScan.LIVE_BARCODE
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun startLabelScan() {
        if (hasCameraPermission()) {
            cameraLauncher.launch(null)
        } else {
            pendingScan = PendingScan.LABEL_PHOTO
            permissionLauncher.launch(Manifest.permission.CAMERA)
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

        when (state.phase) {
            AddItemPhase.SCAN_CHOICE -> ScanChoiceContent(
                scanError = state.scanError,
                onScanBarcode = { startBarcodeScan() },
                onScanLabel = { startLabelScan() },
                onManualEntry = { viewModel.proceedToManualEntry() }
            )
            AddItemPhase.SCANNING_LIVE_BARCODE -> LiveBarcodeScanContent(
                onBarcodeDetected = { viewModel.onLiveBarcodeDetected(it) },
                onCancel = { viewModel.cancelLiveBarcodeScan() }
            )
            AddItemPhase.SCANNING -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            AddItemPhase.BARCODE_RESULT -> BarcodeResultContent(
                barcode = state.scannedBarcode,
                decoderUsed = state.decoderUsed,
                matchedItem = state.matchedItem,
                onUseExisting = onDone,
                onCreateNew = { viewModel.proceedToCreateFromBarcode() },
                onRetry = { viewModel.resetToScanChoice() }
            )
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
private fun LiveBarcodeScanContent(
    onBarcodeDetected: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LiveBarcodeScannerView(
            onBarcodeDetected = onBarcodeDetected,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Point the camera at a barcode",
                style = MaterialTheme.typography.bodyLarge,
                color = androidx.compose.ui.graphics.Color.White
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ScanChoiceContent(
    scanError: String?,
    onScanBarcode: () -> Unit,
    onScanLabel: () -> Unit,
    onManualEntry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (scanError != null) {
            Text(
                "Scan failed: $scanError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
        }

        Button(onClick = onScanBarcode, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Barcode")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(6.dp))
        Button(onClick = onScanLabel, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Nutrition Label")
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Text(
            "or",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(12.dp))
        Button(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
            Text("Enter Manually")
        }
    }
}

@Composable
private fun BarcodeResultContent(
    barcode: String?,
    decoderUsed: String?,
    matchedItem: com.mealtracker.android.network.models.Item?,
    onUseExisting: () -> Unit,
    onCreateNew: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (barcode == null) {
            Text(
                "Couldn't read a barcode from that photo.",
                style = MaterialTheme.typography.titleMedium
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try Again")
            }
            return@Column
        }

        // IMPORTANT: always show the decoded digits for the user to
        // visually confirm against the physical package -- real-world
        // testing found decoders can return a wrong value that still
        // looks structurally valid (see AddItemViewModel/Models.kt docs).
        Text("Scanned barcode:", style = MaterialTheme.typography.bodyMedium)
        Text(barcode, style = MaterialTheme.typography.headlineSmall)
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
            Button(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                Text("Create New Item")
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Again")
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
            label = { Text("Barcode (optional)") },
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
