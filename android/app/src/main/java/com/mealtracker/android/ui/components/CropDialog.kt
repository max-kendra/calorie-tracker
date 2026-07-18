package com.mealtracker.android.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_CROP_SIZE_PX = 60f
private const val HANDLE_TOUCH_SIZE_DP = 32

/**
 * Self-contained freeform image cropper - home-grown rather than a
 * third-party library, after TWO separate third-party croppers caused
 * real build breakage in this project: UCrop's transitive
 * androidx.transition dependency didn't resolve correctly at runtime,
 * and easycrop's published 0.1.1 artifact (built against a Compose BOM
 * from early 2023) turned out not to match its own README's documented
 * API when compiled against our current Compose BOM. Owning this
 * ourselves means there's nothing external to go stale or mismatch
 * against a future Compose upgrade.
 *
 * IMPORTANT: this is a plain composable, NOT wrapped in
 * androidx.compose.ui.window.Dialog. It used to be - but Dialog's
 * separate Android Window kept NOT actually sizing to the full screen
 * reliably (DialogProperties flags, then forcing the underlying
 * Window's LayoutParams to MATCH_PARENT via a SideEffect, both still
 * left the Cancel/Rotate/Crop row missing/barely-visible on some
 * devices - reported multiple times). Rendering inline in the CALLER's
 * own window sidesteps that whole class of bug, since that window's
 * insets/sizing are already known to work correctly (same one Journal/
 * MealDetail etc. use). Callers are responsible for layering this on
 * top of their other content themselves (e.g. as a later sibling in an
 * outer Box) so it visually overlays rather than pushing content down
 * in normal Column/Row flow - see AddItemScreen.kt or
 * MealDetailScreen.kt's ItemLogPageDialog for the pattern.
 *
 * Shows the source image fit-to-container with a draggable/resizable
 * crop rectangle on top - drag inside the rectangle to move it, drag a
 * corner handle to resize it. A rotate button turns the image (and the
 * crop rect resets to a centered inset of the new orientation) in 90-
 * degree steps, for photos that come out sideways/upside-down (no EXIF-
 * orientation auto-detection is attempted - this is a manual fix
 * instead, which also covers cases EXIF metadata gets wrong or lacks
 * entirely). "Crop" maps the on-screen rectangle back to source-bitmap
 * pixel coordinates (post-rotation) and produces the cropped Bitmap via
 * Bitmap.createBitmap.
 */
@Composable
fun CropDialog(
    sourceBitmap: Bitmap,
    onCropped: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var rotationDegrees by remember(sourceBitmap) { mutableIntStateOf(0) }
    // Recomputed only when sourceBitmap or rotationDegrees actually
    // change (remember, not derivedStateOf) - rotating a large camera
    // photo isn't free, so this shouldn't re-run on unrelated
    // recompositions (e.g. while dragging the crop rect).
    val displayBitmap = remember(sourceBitmap, rotationDegrees) {
        if (rotationDegrees == 0) {
            sourceBitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
        }
    }

    var imageBounds by remember(displayBitmap) { mutableStateOf(Rect.Zero) }
    var cropRect by remember(displayBitmap) { mutableStateOf(Rect.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val containerWidthPx = constraints.maxWidth.toFloat()
                val containerHeightPx = constraints.maxHeight.toFloat()

                // Derives imageBounds/cropRect once per container size
                // (or when the bitmap changes) rather than every
                // recomposition - also means dragging cropRect around
                // doesn't get reset by unrelated recompositions, since
                // this effect only re-runs when its keys actually change.
                LaunchedEffect(containerWidthPx, containerHeightPx, displayBitmap) {
                    if (containerWidthPx <= 0f || containerHeightPx <= 0f) return@LaunchedEffect

                    val bitmapAspect = displayBitmap.width.toFloat() / displayBitmap.height.toFloat()
                    val containerAspect = containerWidthPx / containerHeightPx

                    val displayedWidth: Float
                    val displayedHeight: Float
                    if (bitmapAspect > containerAspect) {
                        displayedWidth = containerWidthPx
                        displayedHeight = containerWidthPx / bitmapAspect
                    } else {
                        displayedHeight = containerHeightPx
                        displayedWidth = containerHeightPx * bitmapAspect
                    }
                    val offsetX = (containerWidthPx - displayedWidth) / 2f
                    val offsetY = (containerHeightPx - displayedHeight) / 2f
                    val bounds = Rect(offsetX, offsetY, offsetX + displayedWidth, offsetY + displayedHeight)
                    imageBounds = bounds

                    // Defaults to the FULL image now, not an inset -
                    // per design discussion, most photos here (already
                    // framed by the camera/gallery picker) don't need
                    // cropping at all, and a dedicated "Full Image"
                    // button asking the user to opt into that on every
                    // single capture was one extra tap for what's now
                    // the common case. The corner handles sit exactly
                    // on the image edges as a result - still draggable
                    // (each has a 32dp touch target centered on its
                    // position, same as before), just no longer inset
                    // in from the edge by default. Manually cropping
                    // smaller is still fully supported by dragging.
                    cropRect = bounds
                }

                if (imageBounds != Rect.Zero) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dimColor = Color.Black.copy(alpha = 0.55f)
                        drawRect(dimColor, topLeft = Offset(0f, 0f), size = Size(size.width, cropRect.top))
                        drawRect(
                            dimColor,
                            topLeft = Offset(0f, cropRect.bottom),
                            size = Size(size.width, size.height - cropRect.bottom)
                        )
                        drawRect(
                            dimColor,
                            topLeft = Offset(0f, cropRect.top),
                            size = Size(cropRect.left, cropRect.height)
                        )
                        drawRect(
                            dimColor,
                            topLeft = Offset(cropRect.right, cropRect.top),
                            size = Size(size.width - cropRect.right, cropRect.height)
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = cropRect.topLeft,
                            size = cropRect.size,
                            style = Stroke(width = 3f)
                        )
                    }

                    // Move the whole rect by dragging inside it.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(imageBounds) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    cropRect = clampRectWithinBounds(cropRect.translate(dragAmount), imageBounds)
                                }
                            }
                    )

                    CropHandle(positionPx = cropRect.topLeft) { delta ->
                        cropRect = clampResize(
                            Rect(cropRect.left + delta.x, cropRect.top + delta.y, cropRect.right, cropRect.bottom),
                            imageBounds
                        )
                    }
                    CropHandle(positionPx = Offset(cropRect.right, cropRect.top)) { delta ->
                        cropRect = clampResize(
                            Rect(cropRect.left, cropRect.top + delta.y, cropRect.right + delta.x, cropRect.bottom),
                            imageBounds
                        )
                    }
                    CropHandle(positionPx = Offset(cropRect.left, cropRect.bottom)) { delta ->
                        cropRect = clampResize(
                            Rect(cropRect.left + delta.x, cropRect.top, cropRect.right, cropRect.bottom + delta.y),
                            imageBounds
                        )
                    }
                    CropHandle(positionPx = cropRect.bottomRight) { delta ->
                        cropRect = clampResize(
                            Rect(cropRect.left, cropRect.top, cropRect.right + delta.x, cropRect.bottom + delta.y),
                            imageBounds
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color.White)
                }
                IconButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                }
                Button(onClick = {
                    if (imageBounds == Rect.Zero) return@Button
                    val scale = displayBitmap.width / imageBounds.width
                    val srcLeft = ((cropRect.left - imageBounds.left) * scale).roundToInt()
                        .coerceIn(0, displayBitmap.width - 1)
                    val srcTop = ((cropRect.top - imageBounds.top) * scale).roundToInt()
                        .coerceIn(0, displayBitmap.height - 1)
                    val srcWidth = (cropRect.width * scale).roundToInt()
                        .coerceIn(1, displayBitmap.width - srcLeft)
                    val srcHeight = (cropRect.height * scale).roundToInt()
                        .coerceIn(1, displayBitmap.height - srcTop)
                    onCropped(Bitmap.createBitmap(displayBitmap, srcLeft, srcTop, srcWidth, srcHeight))
                }) {
                    Text("Crop")
                }
            }
        }
    }
}

@Composable
private fun CropHandle(positionPx: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val sizePx = with(density) { HANDLE_TOUCH_SIZE_DP.dp.toPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (positionPx.x - sizePx / 2).roundToInt(),
                    (positionPx.y - sizePx / 2).roundToInt()
                )
            }
            .size(HANDLE_TOUCH_SIZE_DP.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(16.dp)
                .background(Color.White, shape = CircleShape)
        )
    }
}

private fun clampRectWithinBounds(rect: Rect, bounds: Rect): Rect {
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    val width = rect.width
    val height = rect.height

    if (left < bounds.left) { left = bounds.left; right = left + width }
    if (top < bounds.top) { top = bounds.top; bottom = top + height }
    if (right > bounds.right) { right = bounds.right; left = right - width }
    if (bottom > bounds.bottom) { bottom = bounds.bottom; top = bottom - height }

    return Rect(left, top, right, bottom)
}

private fun clampResize(rect: Rect, bounds: Rect): Rect {
    var left = rect.left.coerceIn(bounds.left, bounds.right)
    var top = rect.top.coerceIn(bounds.top, bounds.bottom)
    var right = rect.right.coerceIn(bounds.left, bounds.right)
    var bottom = rect.bottom.coerceIn(bounds.top, bounds.bottom)

    if (right - left < MIN_CROP_SIZE_PX) {
        if (left <= bounds.left) right = min(left + MIN_CROP_SIZE_PX, bounds.right) else left = max(right - MIN_CROP_SIZE_PX, bounds.left)
    }
    if (bottom - top < MIN_CROP_SIZE_PX) {
        if (top <= bounds.top) bottom = min(top + MIN_CROP_SIZE_PX, bounds.bottom) else top = max(bottom - MIN_CROP_SIZE_PX, bounds.top)
    }
    return Rect(left, top, right, bottom)
}