package com.mealtracker.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
 * lambda from an onClick to launch the picker.
 *
 * CropDialog itself is a plain composable now, not self-overlaying (see
 * its own doc comment -- Dialog's window kept not sizing to the full
 * screen reliably). AddItemScreen/MealDetailScreen can lay it out as a
 * Box sibling themselves since they control their own structure, but
 * THIS helper is invoked from arbitrary/unknown call sites (Profile,
 * Edit Profile, wherever else) that it has no control over and can't
 * assume are Box-wrapped -- so it still wraps its own CropDialog usage
 * in a local Dialog here, isolating that risk to just this one helper
 * rather than reintroducing it into the shared component.
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
        Dialog(
            onDismissRequest = { cropBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            val view = LocalView.current
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window
                window?.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
            }
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
        }
    }

    return {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}