package com.mealtracker.android.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Live, continuous, on-device barcode scanning -- deliberately NOT a
 * single-photo-capture-then-upload-to-backend flow. This matters for two
 * reasons (see design doc discussion):
 *   1. No network round trip per frame -- feels instant, which a
 *      photo-then-upload flow can't match over Tailscale.
 *   2. Multi-frame consensus is inherently more reliable than a single
 *      still: this requires the SAME decoded value to appear on several
 *      consecutive frames before accepting it, which naturally filters
 *      out one-off misreads that a single photo can't self-correct.
 *
 * IMPORTANT: this hasn't been empirically tested against real product
 * barcodes the way pyzbar/zxing-cpp were (see backend README for that
 * testing) -- ML Kit is Google's on-device model and is generally
 * reliable, but we have no first-party test data for it specifically.
 * The multi-frame consensus requirement here is an extra safety margin
 * given that gap, but the "always show the decoded number for the user
 * to visually confirm against the package" rule from the backend scanner
 * still applies with at least as much reason -- never auto-trust this.
 */
@Composable
fun LiveBarcodeScannerView(
    onBarcodeDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            ContextCompat.getMainExecutor(ctx),
                            BarcodeAnalyzer(onBarcodeDetected)
                        )
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    // Camera unavailable/binding failed -- nothing more we
                    // can do here; the preview just won't show. The
                    // AddItemScreen's camera-permission check happens
                    // before this Composable is even shown, so this is
                    // for genuinely unexpected hardware/binding issues.
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

/**
 * Requires REQUIRED_CONSECUTIVE_MATCHES identical readings in a row
 * before accepting a barcode -- see the file-level doc comment for why.
 */
private class BarcodeAnalyzer(
    private val onStableBarcodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val REQUIRED_CONSECUTIVE_MATCHES = 3
    }

    private val scanner = BarcodeScanning.getClient()
    private var lastValue: String? = null
    private var consecutiveCount = 0
    private var alreadyReported = false

    @SuppressLint("UnsafeOptInUsageError")
    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (alreadyReported) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue

                if (value != null) {
                    if (value == lastValue) {
                        consecutiveCount++
                    } else {
                        lastValue = value
                        consecutiveCount = 1
                    }

                    if (consecutiveCount >= REQUIRED_CONSECUTIVE_MATCHES && !alreadyReported) {
                        alreadyReported = true
                        onStableBarcodeDetected(value)
                    }
                } else {
                    lastValue = null
                    consecutiveCount = 0
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

/**
 * Decodes a barcode from a static image (used for the "pick from
 * gallery instead" path -- e.g. photos taken at the store to review
 * later, see design doc). Same ML Kit client as the live scanner, just
 * fed a single static image instead of a continuous frame stream, so no
 * multi-frame consensus is possible here -- a single decode attempt.
 */
suspend fun decodeBarcodeFromUri(context: Context, uri: Uri): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    continuation.resume(barcodes.firstOrNull()?.rawValue) {}
                }
                .addOnFailureListener {
                    continuation.resume(null) {}
                }
        } catch (e: Exception) {
            continuation.resume(null) {}
        }
    }
