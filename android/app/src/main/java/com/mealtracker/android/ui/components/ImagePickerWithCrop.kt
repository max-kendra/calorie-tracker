package com.mealtracker.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Gallery-pick + crop, packaged as a single reusable unit -- extracted
 * from the pick/decode/crop logic in AddItemScreen.kt so the same flow
 * doesn't get re-implemented for every place that needs "let the user
 * pick and crop an image" (currently: profile picture, from both the
 * Profile screen's avatar and Edit Profile screen).
 *
 * Usage: call this at the top of a Composable, then invoke the returned
 * lambda from an onClick to launch the picker. The CropDialog it manages
 * internally renders itself (as a Dialog, so it overlays regardless of
 * where this is called from) -- no separate step needed to "show" it.
 *
 * Deliberately gallery-only, no live camera capture option -- unlike the
 * nutrition-label/product-photo steps in Add Item, a profile picture
 * doesn't need CameraX's live preview; the system photo picker is a
 * simpler, sufficient way to pick one existing photo.
 */
@Composable
fun rememberImagePickerWithCrop(onCropped: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        pendingUri = uri
    }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }
        cropBitmap = bitmap
        // Consumed -- clears so re-picking the exact same photo still
        // re-triggers this effect (LaunchedEffect only re-runs when its
        // key actually CHANGES, so leaving pendingUri set to the same
        // value would silently no-op on a second identical pick).
        pendingUri = null
    }

    val bitmapToCrop = cropBitmap
    if (bitmapToCrop != null) {
        CropDialog(
            sourceBitmap = bitmapToCrop,
            onCropped = { cropped ->
                val stream = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                cropBitmap = null
                onCropped(stream.toByteArray())
            },
            onCancel = { cropBitmap = null }
        )
    }

    return {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}