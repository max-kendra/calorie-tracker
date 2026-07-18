package com.mealtracker.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Decodes the image at [uri] and rotates/flips it according to its
 * embedded EXIF orientation tag, if any. BitmapFactory.decodeStream() on
 * its own ignores that tag entirely -- camera JPEGs are typically saved
 * in the sensor's native (landscape) pixel layout with an EXIF tag
 * saying how to rotate for display, rather than physically rotating the
 * pixel data itself (cheaper for the camera to write). Skipping this
 * step is exactly why a photo taken with the phone held vertically kept
 * showing up sideways in the crop screen, needing a manual rotate every
 * time (see design discussion) -- it was never a bug in the crop/rotate
 * logic itself, just a step nothing was doing at all.
 *
 * Used everywhere a picked/captured photo gets decoded before cropping
 * (AddItemScreen, MealDetailScreen's ItemLogPageDialog,
 * ImagePickerWithCrop) so the fix lives in one place rather than three
 * copies that could drift out of sync.
 */
fun decodeBitmapWithCorrectOrientation(context: Context, uri: Uri): Bitmap? {
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it)
    } ?: return null

    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        // Not every image has readable EXIF (some gallery picks,
        // already-cropped/re-saved files) -- treat as "no rotation
        // needed" rather than failing the whole decode over it.
        ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        // ORIENTATION_NORMAL or ORIENTATION_UNDEFINED -- nothing to do.
        else -> return bitmap
    }

    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}