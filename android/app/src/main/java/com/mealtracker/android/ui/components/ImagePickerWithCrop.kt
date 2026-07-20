package com.mealtracker.android.ui.components

import android.graphics.Bitmap
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
 * Gallery-pick + crop, packaged as a single reusable unit - extracted
 * from the pick/decode/crop logic in AddItemScreen.kt so the same flow
 * doesn't get re-implemented for every place that needs "let the user
 * pick and crop an image" (currently: profile picture, from both the
 * Profile screen's avatar and Edit Profile screen).
 *
 * Usage: call rememberImagePickerWithCrop() to get a handle, invoke
 * handle.launch() from an onClick to start the picker, and render
 * ImagePickerCropOverlay(handle) as a LATER SIBLING in your own
 * full-screen Box (same pattern as AddItemScreen/MealDetailScreen's own
 * CropDialog usage - see that composable's doc comment).
 *
 * This USED to self-render via an internal Dialog() wrapper, accepting
 * the same "Dialog's separate Android Window doesn't reliably size to
 * the full screen" risk CropDialog's own doc comment describes - a
 * SideEffect forcing the Dialog's window to MATCH_PARENT was tried
 * here too, same as everywhere else that bug showed up, and it was
 * JUST as unreliable (see design discussion: "the CropDialog for the
 * profile picture is out of frame as well, the really old bug" - every
 * OTHER caller of CropDialog had already moved off Dialog() entirely,
 * this was the one remaining holdout, specifically because it's called
 * from screens that don't control their own root layout). Requiring
 * callers to provide their own full-screen Box (which every screen
 * calling this already effectively has, or can easily get) removes the
 * Dialog-window risk entirely, consistent with every other place this
 * bug was actually fixed.
 */
class ImagePickerWithCropHandle internal constructor(
    val launch: () -> Unit,
    internal val cropBitmap: Bitmap?,
    internal val onCropConfirmed: (Bitmap) -> Unit,
    internal val onCropCancelled: () -> Unit
)

@Composable
fun rememberImagePickerWithCrop(onCropped: (ByteArray) -> Unit): ImagePickerWithCropHandle {
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
                decodeBitmapWithCorrectOrientation(context, uri)
            } catch (e: Exception) {
                null
            }
        }
        cropBitmap = bitmap
        // Consumed - clears so re-picking the exact same photo still
        // re-triggers this effect (LaunchedEffect only re-runs when its
        // key actually CHANGES, so leaving pendingUri set to the same
        // value would silently no-op on a second identical pick).
        pendingUri = null
    }

    return ImagePickerWithCropHandle(
        launch = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        cropBitmap = cropBitmap,
        onCropConfirmed = { cropped ->
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            cropBitmap = null
            onCropped(stream.toByteArray())
        },
        onCropCancelled = { cropBitmap = null }
    )
}

/**
 * Renders the actual crop UI when a photo is pending - place this as a
 * LATER SIBLING in your own full-screen Box (see this file's top doc
 * comment). No-ops entirely when nothing is pending, so it's always
 * safe to include unconditionally.
 */
@Composable
fun ImagePickerCropOverlay(handle: ImagePickerWithCropHandle) {
    val bitmap = handle.cropBitmap ?: return
    CropDialog(
        sourceBitmap = bitmap,
        onCropped = handle.onCropConfirmed,
        onCancel = handle.onCropCancelled
    )
}