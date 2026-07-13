package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.ItemCreateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Barcode is now the mandatory first step -- there's no independent
 * "Scan Label" or "Enter Manually" entry point, since without a barcode
 * match there'd be nothing to check against and every new item needs
 * SOME identifying step first (see design doc discussion). Flow:
 *
 *   SCAN_BARCODE (live, on-device, ~8s timeout)
 *     -> BARCODE_LOOKUP -> BARCODE_RESULT
 *         -> matched existing item: done
 *         -> no match: one confirmation tap -> CAPTURE_LABEL
 *   (or, if the timeout fires with nothing detected: a prompt offers
 *    MANUAL_BARCODE_ENTRY, which feeds into the same lookup)
 *
 *   CAPTURE_LABEL (live, in-app camera OR gallery pick)
 *     -> PROCESSING_LABEL -> ITEM_FORM (pre-filled, barcode carried over)
 *         -> SAVING -> SAVED
 *
 * Editing an EXISTING item is a separate, not-yet-built feature (reached
 * from a future My Foods browse screen, not this flow).
 */
enum class AddItemPhase {
    SCAN_BARCODE, BARCODE_LOOKUP, BARCODE_RESULT, MANUAL_BARCODE_ENTRY,
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

    fun resetToScanChoice() {
        _uiState.value = AddItemUiState()
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

            _uiState.value = _uiState.value.copy(
                phase = AddItemPhase.BARCODE_RESULT,
                scannedBarcode = barcode,
                decoderUsed = decoderUsed,
                matchedItem = matched
            )
        }
    }

    /** User confirmed no existing item matches and wants to continue --
     * moves into the (mandatory, since we now have a barcode to attach)
     * label capture step. */
    fun proceedToCaptureLabel() {
        val state = _uiState.value
        _uiState.value = state.copy(
            phase = AddItemPhase.CAPTURE_LABEL,
            barcode = state.scannedBarcode ?: ""
        )
    }

    fun retryBarcodeScan() {
        _uiState.value = _uiState.value.copy(
            phase = AddItemPhase.SCAN_BARCODE,
            scanError = null,
            showManualEntryPrompt = false
        )
    }

    // ----- Label step -----

    fun scanLabel(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.PROCESSING_LABEL, scanError = null)
        viewModelScope.launch {
            try {
                val result = ApiClient.service.scanLabel(imageBytesToPart(imageBytes))
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.ITEM_FORM,
                    ocrDetectedLanguage = result.detectedLanguage,
                    ocrPer100gConfirmed = result.per100gConfirmed,
                    ocrWasUsed = true,
                    kcal100g = result.macros.kcal100g ?: "",
                    protein100g = result.macros.protein100g ?: "",
                    carbs100g = result.macros.carbs100g ?: "",
                    fat100g = result.macros.fat100g ?: "",
                    fiber100g = result.macros.fiber100g ?: "",
                    sugar100g = result.macros.sugar100g ?: "",
                    saturatedFat100g = result.macros.saturatedFat100g ?: "",
                    sodiumMg100g = result.macros.sodiumMg100g ?: ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_LABEL,
                    scanError = e.message ?: "Label scan failed"
                )
            }
        }
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
