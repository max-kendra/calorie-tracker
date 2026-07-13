package com.mealtracker.android.ui.components

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * Live in-app camera preview for capturing a nutrition label photo --
 * deliberately our OWN camera UI (CameraX Preview + ImageCapture bound
 * together) rather than launching the system camera app, so there's no
 * jarring app-switch transition when adding an item (see design doc).
 *
 * This component only binds the camera and hands the resulting
 * ImageCapture use case back via `onImageCaptureReady` -- the actual
 * "take the photo" button lives in the calling screen (AddItemScreen),
 * which holds onto that ImageCapture reference and calls
 * `.takePicture(...)` on it directly when the user taps capture. This
 * split keeps the camera-binding logic here reusable/self-contained
 * while letting the caller design its own capture-button UI/overlay.
 */
@Composable
fun LiveLabelCaptureView(
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCameraReady: (Camera) -> Unit = {},
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

                val imageCapture = ImageCapture.Builder().build()

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                    onImageCaptureReady(imageCapture)
                    onCameraReady(camera)
                } catch (e: Exception) {
                    // Camera unavailable/binding failed -- the preview
                    // just won't show; permission is already checked
                    // before this Composable is shown.
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
