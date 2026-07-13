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

enum class AddItemPhase {
    SCAN_CHOICE, SCANNING_LIVE_BARCODE, SCANNING, BARCODE_RESULT, ITEM_FORM, SAVING, SAVED
}

/**
 * Single screen, multiple internal phases (rather than separate nav
 * destinations passing scan results between them) -- keeps the
 * scan->review->form->save flow cohesive and avoids needing to pass
 * structured scan data (OCR macros, barcode results) through navigation
 * arguments, which only support simple types cleanly.
 */
data class AddItemUiState(
    val phase: AddItemPhase = AddItemPhase.SCAN_CHOICE,
    val scanError: String? = null,

    // Barcode scan result -- see BarcodeScanResult's doc comment
    // (Models.kt) for why checksumValid is NOT proof of correctness and
    // the barcode must always be shown for user confirmation.
    val scannedBarcode: String? = null,
    val decoderUsed: String? = null,
    val checksumValid: Boolean? = null,
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

    fun startLiveBarcodeScan() {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.SCANNING_LIVE_BARCODE, scanError = null)
    }

    /**
     * Called when the on-device live scanner (see LiveBarcodeScannerView --
     * requires several consecutive matching frames before calling this)
     * settles on a value. We already have the decoded string here, so
     * this does a direct barcode lookup rather than uploading anything.
     *
     * IMPORTANT: still surfaces the raw decoded value for the user to
     * visually confirm against the package -- multi-frame consensus
     * reduces misreads but doesn't eliminate the need for confirmation
     * (see LiveBarcodeScannerView's doc comment).
     */
    fun onLiveBarcodeDetected(barcode: String) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.SCANNING)
        viewModelScope.launch {
            val matched = try {
                ApiClient.service.getItemByBarcode(barcode)
            } catch (e: HttpException) {
                if (e.code() == 404) null else {
                    _uiState.value = _uiState.value.copy(
                        phase = AddItemPhase.SCAN_CHOICE,
                        scanError = "Lookup failed: ${e.message() ?: "server error"}"
                    )
                    return@launch
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.SCAN_CHOICE,
                    scanError = e.message ?: "Lookup failed"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                phase = AddItemPhase.BARCODE_RESULT,
                scannedBarcode = barcode,
                decoderUsed = "ML Kit (on-device)",
                checksumValid = null, // not computed client-side
                matchedItem = matched
            )
        }
    }

    fun cancelLiveBarcodeScan() {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.SCAN_CHOICE)
    }

    fun resetToScanChoice() {
        _uiState.value = AddItemUiState()
    }

    private fun imageBytesToPart(bytes: ByteArray): MultipartBody.Part {
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
    }

    // Note: the backend's POST /items/scan-barcode endpoint (photo upload
    // + server-side decode) is still available in ApiService for
    // potential future use (e.g. scanning from a gallery photo), but the
    // primary flow is now live/on-device scanning -- see
    // onLiveBarcodeDetected() below and LiveBarcodeScannerView.

    fun scanLabel(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.SCANNING, scanError = null)
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
                    phase = AddItemPhase.SCAN_CHOICE,
                    scanError = e.message ?: "Label scan failed"
                )
            }
        }
    }

    /** User confirmed the scanned barcode is correct and no item exists
     * yet -- proceed to the form with the barcode pre-filled. */
    fun proceedToCreateFromBarcode() {
        val state = _uiState.value
        _uiState.value = state.copy(
            phase = AddItemPhase.ITEM_FORM,
            barcode = state.scannedBarcode ?: ""
        )
    }

    /** Scan failed or was rejected by the user -- fall back to a blank
     * form with manual barcode entry. */
    fun proceedToManualEntry() {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.ITEM_FORM)
    }

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
