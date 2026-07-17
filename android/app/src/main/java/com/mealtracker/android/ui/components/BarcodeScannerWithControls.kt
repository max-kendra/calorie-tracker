package com.mealtracker.android.ui.components

import androidx.camera.core.Camera
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The full-featured live barcode scanner: camera preview + torch toggle
 * + "pick from gallery instead" -- this is meant to be THE ONE camera UI
 * used everywhere a barcode gets scanned in the app, rather than each
 * call site building its own wrapper around the bare LiveBarcodeScannerView.
 *
 * Previously AddItemScreen had this built out privately (torch, gallery,
 * instructional overlay) while MealDetailScreen's in-sheet barcode mode
 * called LiveBarcodeScannerView directly with none of that -- two
 * different-feeling cameras in the same app depending on where you
 * entered from. Extracted here so both call sites share one
 * implementation and can't drift apart again.
 */
@Composable
fun BarcodeScannerWithControls(
    onBarcodeDetected: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
    scanError: String? = null,
    instructionText: String = "Point the camera at a barcode"
) {
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
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