package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.ItemCreateRequest
import com.mealtracker.android.network.models.LogCreateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Flat default for logging whatever comes out of this flow (a newly
// created item, or an existing one picked via barcode match) straight
// to a meal -- no quantity/serving picker here yet, same simplification
// as MealDetailViewModel's QUICK_LOG_QUANTITY_G, and for the same
// reason: this is a fast-path convenience, not a substitute for
// accurate quantity entry. Revisit once a quantity step exists.
private const val MEAL_LOG_QUANTITY_G = 100.0

/**
 * Barcode is now the mandatory first step -- there's no independent
 * "Scan Label" or "Enter Manually" entry point, since without a barcode
 * match there'd be nothing to check against and every new item needs
 * SOME identifying step first (see design doc discussion). Flow:
 *
 *   SCAN_BARCODE (live, on-device, ~8s timeout)
 *     -> BARCODE_LOOKUP -> BARCODE_RESULT
 *         -> matched existing item: done
 *         -> no match: one confirmation tap -> CAPTURE_PRODUCT_PHOTO
 *   (or, if the timeout fires with nothing detected: a prompt offers
 *    MANUAL_BARCODE_ENTRY, which feeds into the same lookup)
 *
 *   CAPTURE_PRODUCT_PHOTO (live, in-app camera OR gallery pick; always
 *   cropped client-side before upload, same as the label step)
 *     -> PROCESSING_PRODUCT_PHOTO -> CAPTURE_LABEL (name/brand/image
 *        carried into state, pre-filled but still editable in the
 *        eventual form -- see scanProductPhoto())
 *
 *   CAPTURE_LABEL (live, in-app camera OR gallery pick)
 *     -> PROCESSING_LABEL -> ITEM_FORM (pre-filled, barcode/name/brand/
 *        image carried over)
 *         -> SAVING -> SAVED
 *
 * Editing an EXISTING item is a separate, not-yet-built feature (reached
 * from a future My Foods browse screen, not this flow).
 */
enum class AddItemPhase {
    SCAN_BARCODE, BARCODE_LOOKUP, BARCODE_RESULT, MANUAL_BARCODE_ENTRY,
    CAPTURE_PRODUCT_PHOTO, PROCESSING_PRODUCT_PHOTO,
    CAPTURE_LABEL, PROCESSING_LABEL, ITEM_FORM, SAVING, SAVED
}

data class AddItemUiState(
    val phase: AddItemPhase = AddItemPhase.SCAN_BARCODE,
    val scanError: String? = null,

    // Shown when the live barcode scan times out with nothing detected.
    val showManualEntryPrompt: Boolean = false,
    val manualBarcodeInput: String = "",

    // Barcode result -- see LiveBarcodeScannerView's doc comment for why
    // the barcode must always be shown for user confirmation, regardless
    // of decoder/source.
    val scannedBarcode: String? = null,
    val decoderUsed: String? = null,
    val matchedItem: Item? = null,

    // OCR context, shown as info/warnings in the item form
    val ocrDetectedLanguage: String? = null,
    val ocrPer100gConfirmed: Boolean = true,
    val ocrWasUsed: Boolean = false,

    // Product photo step -- image_path comes back from the backend once
    // uploaded (see scanProductPhoto()) and is carried through to the
    // final POST /items call so the photo gets attached to the item.
    // guessedName/guessedBrand are rough OCR heuristics used ONLY to
    // pre-fill `name`/`brand` below -- not kept as separate fields, since
    // once they've pre-filled the (still-editable) form fields there's
    // nothing else that needs to reference the original guess.
    val productImagePath: String? = null,

    // Shown when OCR genuinely couldn't extract anything useful from the
    // label photo (not just a network/upload error, which uses scanError
    // instead) -- offers retake or skip-to-manual-entry rather than
    // silently handing the user an empty form with no explanation.
    val showOcrFailedDialog: Boolean = false,

    // Item form fields -- all Strings for TextField editing, parsed on save
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val itemType: String = "product", // "product" | "ingredient"
    val kcal100g: String = "",
    val protein100g: String = "",
    val carbs100g: String = "",
    val fat100g: String = "",
    val fiber100g: String = "",
    val sugar100g: String = "",
    val saturatedFat100g: String = "",
    val sodiumMg100g: String = "",

    val saveError: String? = null,
    val createdItem: Item? = null
)

class AddItemViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AddItemUiState())
    val uiState: StateFlow<AddItemUiState> = _uiState

    // Set once (see MealDetailScreen, which embeds this whole flow
    // inline in its Add Item sheet) so saveItem()/useMatchedItem() know
    // which meal to attach a log to. Null when this flow is reached any
    // other way -- in that case it still creates/matches the Item, just
    // without also logging it anywhere (which used to be the ONLY
    // behavior, before this flow was embeddable in a meal's context).
    private var mealContext: Pair<LocalDate, String>? = null

    fun attachToMeal(date: LocalDate, mealType: String) {
        mealContext = date to mealType
    }

    private suspend fun logToMealIfAttached(itemId: Int) {
        val (date, mealType) = mealContext ?: return
        ApiClient.service.createLog(
            LogCreateRequest(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                mealType = mealType,
                itemId = itemId,
                quantity = MEAL_LOG_QUANTITY_G
            )
        )
    }

    fun resetToScanChoice() {
        _uiState.value = AddItemUiState()
    }

    /** Matched an existing item via barcode (BARCODE_RESULT phase) --
     * previously this just ended the flow with no further action; now
     * logs it to the attached meal (if any) same as a newly-saved item
     * does, then shows the same SAVED confirmation screen. */
    fun useMatchedItem() {
        val item = _uiState.value.matchedItem ?: return
        viewModelScope.launch {
            try {
                logToMealIfAttached(item.itemId)
            } catch (e: Exception) {
                // Logging is the point of embedding this flow in a meal
                // context, but the item match itself is still valid --
                // don't block showing it as done over a logging hiccup,
                // just surface the error alongside.
                _uiState.value = _uiState.value.copy(saveError = e.message ?: "Couldn't log this item to the meal")
            }
            _uiState.value = _uiState.value.copy(phase = AddItemPhase.SAVED, createdItem = item)
        }
    }

    private fun imageBytesToPart(bytes: ByteArray): MultipartBody.Part {
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
    }

    // ----- Barcode step -----

    /** Called by the Composable's timeout timer if SCAN_BARCODE has been
     * showing for ~8s with nothing detected. */
    fun onBarcodeTimeout() {
        if (_uiState.value.phase == AddItemPhase.SCAN_BARCODE) {
            _uiState.value = _uiState.value.copy(showManualEntryPrompt = true)
        }
    }

    fun dismissManualEntryPrompt() {
        _uiState.value = _uiState.value.copy(showManualEntryPrompt = false)
    }

    fun proceedToManualBarcodeEntry() {
        _uiState.value = _uiState.value.copy(
            phase = AddItemPhase.MANUAL_BARCODE_ENTRY,
            showManualEntryPrompt = false
        )
    }

    fun updateManualBarcodeInput(value: String) {
        _uiState.value = _uiState.value.copy(manualBarcodeInput = value)
    }

    fun submitManualBarcode() {
        val barcode = _uiState.value.manualBarcodeInput.trim()
        if (barcode.isEmpty()) return
        lookUpBarcode(barcode, decoderUsed = "Manual entry")
    }

    /** Called when the live scanner (multi-frame consensus, see
     * LiveBarcodeScannerView) settles on a value. */
    fun onLiveBarcodeDetected(barcode: String) {
        lookUpBarcode(barcode, decoderUsed = "ML Kit (on-device)")
    }

    /** Called after a gallery-picked image was run through
     * decodeBarcodeFromUri() in the Composable (needs Context, so the
     * actual decode call happens there, not here). */
    fun onGalleryBarcodeResult(barcode: String?) {
        if (barcode == null) {
            _uiState.value = _uiState.value.copy(
                scanError = "Couldn't find a barcode in that photo"
            )
            return
        }
        lookUpBarcode(barcode, decoderUsed = "ML Kit (from photo)")
    }

    private fun lookUpBarcode(barcode: String, decoderUsed: String) {
        _uiState.value = _uiState.value.copy(
            phase = AddItemPhase.BARCODE_LOOKUP,
            showManualEntryPrompt = false,
            scanError = null
        )
        viewModelScope.launch {
            val matched = try {
                ApiClient.service.getItemByBarcode(barcode)
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    null
                } else {
                    _uiState.value = _uiState.value.copy(
                        phase = AddItemPhase.SCAN_BARCODE,
                        scanError = "Lookup failed: ${e.message() ?: "server error"}"
                    )
                    return@launch
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.SCAN_BARCODE,
                    scanError = e.message ?: "Lookup failed"
                )
                return@launch
            }

            if (matched == null) {
                // No existing item for this barcode -- per design
                // discussion, don't stop and make the user tap a
                // confirmation button to proceed; we already have the
                // barcode captured, so just continue straight into
                // adding a new item. BARCODE_RESULT is now ONLY used to
                // show a matched item (see below), not as a "nothing
                // found, continue?" prompt.
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_PRODUCT_PHOTO,
                    scannedBarcode = barcode,
                    decoderUsed = decoderUsed,
                    matchedItem = null,
                    barcode = barcode
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                phase = AddItemPhase.BARCODE_RESULT,
                scannedBarcode = barcode,
                decoderUsed = decoderUsed,
                matchedItem = matched
            )
        }
    }

    fun retryBarcodeScan() {
        _uiState.value = _uiState.value.copy(
            phase = AddItemPhase.SCAN_BARCODE,
            scanError = null,
            showManualEntryPrompt = false
        )
    }

    // ----- Product photo step -----

    /** Uploads the cropped product-package photo -- saves it server-side
     * (image_path comes back for us to carry through to the eventual
     * item save) and pre-fills name/brand with OCR's best-effort guess.
     * Always proceeds to CAPTURE_LABEL on success, even if OCR found
     * nothing useful to guess from -- unlike scanLabel()'s failure
     * handling, there's no "couldn't read anything" dialog here, since
     * an unhelpful guess just means the name/brand fields start blank,
     * same as manual entry always was; it's not a dead end the way a
     * fully-failed label scan is. */
    fun scanProductPhoto(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.PROCESSING_PRODUCT_PHOTO, scanError = null)
        viewModelScope.launch {
            try {
                val result = ApiClient.service.scanProductPhoto(imageBytesToPart(imageBytes))
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_LABEL,
                    productImagePath = result.imagePath,
                    name = result.guessedName ?: _uiState.value.name,
                    brand = result.guessedBrand ?: _uiState.value.brand
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_PRODUCT_PHOTO,
                    scanError = e.message ?: "Product photo upload failed"
                )
            }
        }
    }

    /** User chose to skip the product photo entirely -- barcode carries
     * over, name/brand/image stay blank for manual entry later. */
    fun skipProductPhoto() {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.CAPTURE_LABEL)
    }

    // ----- Label step -----

    fun scanLabel(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.PROCESSING_LABEL, scanError = null)
        viewModelScope.launch {
            try {
                val result = ApiClient.service.scanLabel(imageBytesToPart(imageBytes))
                val macros = result.macros
                val extractedNothing = result.detectedLanguage == null &&
                    macros.kcal100g == null && macros.protein100g == null &&
                    macros.carbs100g == null && macros.fat100g == null &&
                    macros.fiber100g == null && macros.sugar100g == null &&
                    macros.saturatedFat100g == null && macros.sodiumMg100g == null

                if (extractedNothing) {
                    // A genuine "couldn't read this label" case -- offer
                    // retake or skip-to-manual rather than silently
                    // handing over an empty form.
                    _uiState.value = _uiState.value.copy(
                        phase = AddItemPhase.CAPTURE_LABEL,
                        showOcrFailedDialog = true
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.ITEM_FORM,
                    ocrDetectedLanguage = result.detectedLanguage,
                    ocrPer100gConfirmed = result.per100gConfirmed,
                    ocrWasUsed = true,
                    kcal100g = macros.kcal100g ?: "",
                    protein100g = macros.protein100g ?: "",
                    carbs100g = macros.carbs100g ?: "",
                    fat100g = macros.fat100g ?: "",
                    fiber100g = macros.fiber100g ?: "",
                    sugar100g = macros.sugar100g ?: "",
                    saturatedFat100g = macros.saturatedFat100g ?: "",
                    sodiumMg100g = macros.sodiumMg100g ?: ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_LABEL,
                    scanError = e.message ?: "Label scan failed"
                )
            }
        }
    }

    fun dismissOcrFailedDialog() {
        _uiState.value = _uiState.value.copy(showOcrFailedDialog = false)
    }

    /** User chose to skip OCR entirely and fill in macros by hand --
     * barcode carries over, everything else starts blank. */
    fun proceedToManualFormFromOcrFailure() {
        _uiState.value = _uiState.value.copy(
            phase = AddItemPhase.ITEM_FORM,
            showOcrFailedDialog = false,
            ocrWasUsed = false
        )
    }

    // ----- Item form -----

    fun updateName(value: String) { _uiState.value = _uiState.value.copy(name = value) }
    fun updateBrand(value: String) { _uiState.value = _uiState.value.copy(brand = value) }
    fun updateBarcode(value: String) { _uiState.value = _uiState.value.copy(barcode = value) }
    fun updateItemType(value: String) { _uiState.value = _uiState.value.copy(itemType = value) }
    fun updateKcal(value: String) { _uiState.value = _uiState.value.copy(kcal100g = value) }
    fun updateProtein(value: String) { _uiState.value = _uiState.value.copy(protein100g = value) }
    fun updateCarbs(value: String) { _uiState.value = _uiState.value.copy(carbs100g = value) }
    fun updateFat(value: String) { _uiState.value = _uiState.value.copy(fat100g = value) }
    fun updateFiber(value: String) { _uiState.value = _uiState.value.copy(fiber100g = value) }
    fun updateSugar(value: String) { _uiState.value = _uiState.value.copy(sugar100g = value) }
    fun updateSaturatedFat(value: String) { _uiState.value = _uiState.value.copy(saturatedFat100g = value) }
    fun updateSodium(value: String) { _uiState.value = _uiState.value.copy(sodiumMg100g = value) }

    fun saveItem() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(saveError = "Name is required")
            return
        }

        _uiState.value = state.copy(phase = AddItemPhase.SAVING, saveError = null)

        viewModelScope.launch {
            try {
                val item = ApiClient.service.createItem(
                    ItemCreateRequest(
                        name = state.name,
                        barcode = state.barcode.ifBlank { null },
                        brand = state.brand.ifBlank { null },
                        imagePath = state.productImagePath,
                        kcal100g = state.kcal100g.toDoubleOrNull(),
                        protein100g = state.protein100g.toDoubleOrNull(),
                        carbs100g = state.carbs100g.toDoubleOrNull(),
                        fat100g = state.fat100g.toDoubleOrNull(),
                        fiber100g = state.fiber100g.toDoubleOrNull(),
                        sugar100g = state.sugar100g.toDoubleOrNull(),
                        saturatedFat100g = state.saturatedFat100g.toDoubleOrNull(),
                        sodiumMg100g = state.sodiumMg100g.toDoubleOrNull(),
                        type = state.itemType,
                        origin = if (state.ocrWasUsed) "ocr_assisted" else "manual"
                    )
                )
                try {
                    logToMealIfAttached(item.itemId)
                } catch (e: Exception) {
                    // Same reasoning as useMatchedItem(): the item itself
                    // saved fine, don't lose that over a logging hiccup.
                    _uiState.value = _uiState.value.copy(saveError = e.message ?: "Couldn't log this item to the meal")
                }

                _uiState.value = _uiState.value.copy(phase = AddItemPhase.SAVED, createdItem = item)
            } catch (e: HttpException) {
                val message = if (e.code() == 409) {
                    "An item with this barcode already exists"
                } else {
                    e.message() ?: "Failed to save"
                }
                _uiState.value = _uiState.value.copy(phase = AddItemPhase.ITEM_FORM, saveError = message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.ITEM_FORM,
                    saveError = e.message ?: "Failed to save"
                )
            }
        }
    }
}