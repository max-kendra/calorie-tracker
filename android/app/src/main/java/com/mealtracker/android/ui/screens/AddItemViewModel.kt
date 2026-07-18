package com.mealtracker.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.network.ApiClient
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.ItemCreateRequest
import com.mealtracker.android.network.models.LogCreateRequest
import com.mealtracker.android.network.models.UsdaFoodSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

// How long to wait after the last keystroke before actually firing a
// USDA search request -- see updateUsdaQuery's doc comment.
private const val USDA_SEARCH_DEBOUNCE_MS = 350L

// Standard rounded salt<->sodium conversion (salt is ~39.3% sodium by
// mass; EU nutrition labels round this to salt = sodium x 2.5, i.e.
// sodium = salt / 2.5). Used at the boundaries of AddItemUiState.
// saltG100g -- see that field's doc comment.
private const val SALT_TO_SODIUM_RATIO = 2.5

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
    // Reached only via jumpToUsdaSearch() from the meal's text Search
    // tab now, not from barcode scanning -- see design discussion ("the
    // barcode flow was good exactly the way it was").
    USDA_SEARCH,
    // A no-match barcode lands here now, BEFORE any photo -- asking the
    // user directly for name/brand instead of guessing them from OCR on
    // the product photo (see design discussion: OCR-guessed name/brand
    // was unreliable in general and especially hopeless for non-Latin
    // packaging -- e.g. Japanese/Korean/Chinese -- since EasyOCR here is
    // only configured for Latin-script languages and won't read those
    // characters at all, let alone guess which line is the name).
    ENTER_NAME_BRAND,
    // Photo-only now -- no OCR runs against this one at all, purely for
    // the hero/icon image. Nutrition values only ever come from
    // CAPTURE_LABEL below.
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
    // Set on a barcode match instead of navigating away -- shown as a
    // toast over the still-live camera (see design discussion). Null
    // once dismissed, added directly, or tapped through to
    // BARCODE_RESULT for more detail.
    val matchedItemToast: Item? = null,

    // OCR context, shown as info/warnings in the item form
    val ocrDetectedLanguage: String? = null,
    val ocrPer100gConfirmed: Boolean = true,
    val ocrWasUsed: Boolean = false,
    val usdaImportUsed: Boolean = false,

    // Raw-ingredient (USDA) search -- see USDA_SEARCH phase, reached
    // from the meal's text Search tab.
    val usdaQuery: String = "",
    val usdaResults: List<UsdaFoodSummary> = emptyList(),
    val isSearchingUsda: Boolean = false,
    val usdaError: String? = null,

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
    // SALT, not sodium -- food labels show salt (that's literally what's
    // printed on the package: "Salt: 0.5g"), and asking someone to
    // mentally convert to sodium themselves invites mistakes. Backend
    // still stores/logs SODIUM in mg (ItemCreateRequest.sodiumMg100g) --
    // conversion happens at the boundaries (see prefillFromOcr and
    // saveItem below) using the standard salt-to-sodium ratio (salt is
    // ~40% sodium by mass, i.e. salt_g \u00d7 2.5 \u2248 sodium_g -- the
    // same rounded factor used on EU nutrition labels). This field
    // itself always holds SALT in grams, never sodium.
    val saltG100g: String = "",

    val saveError: String? = null,
    val createdItem: Item? = null,
    // SAVED screen's "Add Item" text -- see logCreatedItemToMeal.
    val isLoggingToMeal: Boolean = false
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

    /** SAVED screen's small "Add Item" text -- the explicit, opt-in way
     * to log the just-saved/matched item to this meal, now that saving/
     * matching itself no longer does this automatically (see design
     * discussion: "would you like to add this item to your meal?",
     * asked rather than assumed). onComplete fires either way (success
     * or failure) since a logging hiccup here shouldn't trap the user
     * on this screen -- matches the "unobtrusive" framing this was
     * asked for. */
    fun logCreatedItemToMeal(onComplete: () -> Unit) {
        val item = _uiState.value.createdItem
        if (item == null) {
            onComplete()
            return
        }
        _uiState.value = _uiState.value.copy(isLoggingToMeal = true)
        viewModelScope.launch {
            try {
                logToMealIfAttached(item.itemId)
            } catch (e: Exception) {
                // Best-effort -- still proceed, see doc comment above.
            }
            _uiState.value = _uiState.value.copy(isLoggingToMeal = false)
            onComplete()
        }
    }

    fun resetToScanChoice() {
        _uiState.value = AddItemUiState()
    }

    /** Matched an existing item via barcode (BARCODE_RESULT phase) --
     * just shows the SAVED confirmation screen now, same as a newly-
     * saved item. Used to auto-log it to the attached meal here, but
     * logging is decoupled from matching/saving now -- see SAVED
     * screen's explicit "Add Item" action (logCreatedItemToMeal) for
     * why ("would you like to add this item to your meal?", asked
     * rather than assumed, per design discussion). */
    fun useMatchedItem() {
        val item = _uiState.value.matchedItem ?: return
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.SAVED, createdItem = item)
    }

    private fun imageBytesToPart(bytes: ByteArray): MultipartBody.Part {
        val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("image", "photo.jpg", requestBody)
    }

    // ----- Barcode step -----

    /** Called by the Composable's timeout timer if SCAN_BARCODE has been
     * showing for ~8s with nothing detected. */
    fun onBarcodeTimeout() {
        // If a toast is showing, we clearly DID detect and match a
        // barcode -- "no barcode detected" would be actively wrong here,
        // and it kept firing even while a match was actively displayed
        // (see design discussion).
        if (_uiState.value.phase == AddItemPhase.SCAN_BARCODE && _uiState.value.matchedItemToast == null) {
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
    /** ML Kit's analyzer calls back on EVERY frame it sees a barcode, not
     * just once -- without a guard here, that meant re-running the
     * lookup (and replacing the toast's state, causing a visible
     * flicker/reset) many times a second for as long as the same code
     * stayed in frame. Ignore further detections while a toast for a
     * match is already showing or a lookup is already in flight. */
    fun onLiveBarcodeDetected(barcode: String) {
        val state = _uiState.value
        if (state.matchedItemToast != null || state.phase == AddItemPhase.BARCODE_LOOKUP) return
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
                // No existing item for this barcode -- don't stop and
                // make the user tap a confirmation button just to
                // proceed; we already have the barcode captured, so go
                // straight into adding a new item (this part of the
                // original behavior stays -- do NOT reintroduce a "what
                // kind of item" prompt here, see design discussion: "the
                // barcode flow was good exactly the way it was").
                // Raw-ingredient/USDA lookup lives only under the text
                // Search tab now (see jumpToUsdaSearch below), not
                // injected into scanning.
                //
                // Goes to ENTER_NAME_BRAND now, not straight to the
                // photo step -- see that phase's doc comment.
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.ENTER_NAME_BRAND,
                    scannedBarcode = barcode,
                    decoderUsed = decoderUsed,
                    matchedItem = null,
                    barcode = barcode
                )
                return@launch
            }

            // Matched -- stays on SCAN_BARCODE (camera keeps running) and
            // shows a toast over it instead of navigating to a separate
            // full-screen result, per design discussion. Tapping the
            // toast (openMatchedItemToastDetail) reuses BARCODE_RESULT as
            // the "item info" view; tapping its own Add button
            // (useMatchedItem) logs it directly without that detour.
            _uiState.value = _uiState.value.copy(
                phase = AddItemPhase.SCAN_BARCODE,
                scannedBarcode = barcode,
                decoderUsed = decoderUsed,
                matchedItem = matched,
                matchedItemToast = matched
            )
        }
    }

    fun dismissMatchedItemToast() {
        _uiState.value = _uiState.value.copy(matchedItemToast = null, matchedItem = null)
    }

    /** Reached only from the Search tab now, not from scanning -- see
     * design discussion. Jumps straight into USDA_SEARCH without going
     * through SCAN_BARCODE at all. initialQuery carries over whatever
     * the user already typed in the meal's own search box, so they
     * don't have to type the same thing twice (see design discussion). */
    fun jumpToUsdaSearch(initialQuery: String = "") {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.USDA_SEARCH)
        if (initialQuery.isNotBlank()) {
            updateUsdaQuery(initialQuery)
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
    /** Photo-only now -- no OCR runs against this at all (see
     * ENTER_NAME_BRAND's doc comment for why name/brand come from the
     * user directly instead of an OCR guess here). The backend endpoint
     * still happens to run OCR/guess internally (it's shared with the
     * image-update flow elsewhere), but those guessed fields are
     * deliberately ignored here now -- name/brand are already set. */
    fun scanProductPhoto(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.PROCESSING_PRODUCT_PHOTO, scanError = null)
        viewModelScope.launch {
            try {
                val result = ApiClient.service.scanProductPhoto(imageBytesToPart(imageBytes))
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_LABEL,
                    productImagePath = result.imagePath
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.CAPTURE_PRODUCT_PHOTO,
                    scanError = e.message ?: "Product photo upload failed"
                )
            }
        }
    }

    /** Attaches/replaces a photo directly from within ITEM_FORM -- for
     * items that skipped the usual photo-capture steps entirely (USDA
     * imports currently; jumpToUsdaSearch goes straight to ITEM_FORM
     * with no photo step). Doesn't change phase, unlike
     * scanProductPhoto() above -- this is meant to be usable at any
     * point while reviewing the form, not a step in the linear flow. */
    fun attachPhotoToForm(imageBytes: ByteArray) {
        viewModelScope.launch {
            try {
                val result = ApiClient.service.scanProductPhoto(imageBytesToPart(imageBytes))
                _uiState.value = _uiState.value.copy(productImagePath = result.imagePath)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(saveError = e.message ?: "Couldn't upload photo")
            }
        }
    }

    /** User chose to skip the product photo entirely -- barcode carries
     * over, name/brand/image stay blank for manual entry later. */
    fun skipProductPhoto() {
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.CAPTURE_LABEL)
    }

    // Cancelled and relaunched on every keystroke -- see updateUsdaQuery's
    // debounce below.
    private var usdaSearchJob: Job? = null

    /** Debounced now -- this was firing a request on every single
     * keystroke (see design discussion: "I think it's because we send a
     * request every single stroke"), which was very likely a real
     * contributor to the USDA 502s seen in practice -- FDC's API is
     * already rate-limited (especially on the shared DEMO_KEY, see
     * app/routers/usda.py's error message), and typing a 10-character
     * search term used to mean 10 separate requests instead of one. */
    fun updateUsdaQuery(query: String) {
        _uiState.value = _uiState.value.copy(usdaQuery = query)
        usdaSearchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(usdaResults = emptyList(), isSearchingUsda = false)
            return
        }
        _uiState.value = _uiState.value.copy(isSearchingUsda = true, usdaError = null)
        usdaSearchJob = viewModelScope.launch {
            delay(USDA_SEARCH_DEBOUNCE_MS)
            try {
                val results = ApiClient.service.searchUsda(query = query)
                // Guard against a slower earlier search landing after a
                // newer one -- mostly redundant now that the job itself
                // gets cancelled, kept as an extra safety net.
                if (_uiState.value.usdaQuery == query) {
                    _uiState.value = _uiState.value.copy(isSearchingUsda = false, usdaResults = results)
                }
            } catch (e: Exception) {
                if (_uiState.value.usdaQuery == query) {
                    _uiState.value = _uiState.value.copy(
                        isSearchingUsda = false,
                        usdaError = e.message ?: "Search failed"
                    )
                }
            }
        }
    }

    /** Picked a USDA result -- fetches full detail and pre-fills the
     * item form, same pattern as scanLabel()'s OCR prefill below. */
    fun selectUsdaFood(fdcId: Int) {
        viewModelScope.launch {
            try {
                val detail = ApiClient.service.getUsdaFood(fdcId)
                val macros = detail.macros
                _uiState.value = _uiState.value.copy(
                    phase = AddItemPhase.ITEM_FORM,
                    name = detail.description,
                    itemType = "ingredient",
                    usdaImportUsed = true,
                    kcal100g = macros.kcal100g ?: "",
                    protein100g = macros.protein100g ?: "",
                    carbs100g = macros.carbs100g ?: "",
                    fat100g = macros.fat100g ?: "",
                    fiber100g = macros.fiber100g ?: "",
                    sugar100g = macros.sugar100g ?: "",
                    saturatedFat100g = macros.saturatedFat100g ?: "",
                    // USDA reports sodium in mg too -- same conversion as
                    // OCR's prefill.
                    // sodium (mg) -> salt (g): divide by 1000 for the
                    // unit change, multiply by the salt:sodium ratio.
                    saltG100g = macros.sodiumMg100g?.toDoubleOrNull()
                        ?.let { it / 1000.0 * SALT_TO_SODIUM_RATIO }?.toString() ?: ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(usdaError = e.message ?: "Couldn't load that food's details")
            }
        }
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
                    // OCR/backend reports sodium in mg -- convert to g
                    // for display, matching what's printed on the label.
                    // sodium (mg) -> salt (g): divide by 1000 for the
                    // unit change, multiply by the salt:sodium ratio.
                    saltG100g = macros.sodiumMg100g?.toDoubleOrNull()
                        ?.let { it / 1000.0 * SALT_TO_SODIUM_RATIO }?.toString() ?: ""
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

    /** Confirms ENTER_NAME_BRAND -- name is required (it's the whole
     * point of asking directly instead of guessing from OCR), brand is
     * optional, same as the final ITEM_FORM's own validation. */
    fun confirmNameBrand() {
        if (_uiState.value.name.isBlank()) {
            return
        }
        _uiState.value = _uiState.value.copy(phase = AddItemPhase.CAPTURE_PRODUCT_PHOTO)
    }
    fun updateBarcode(value: String) { _uiState.value = _uiState.value.copy(barcode = value) }
    fun updateItemType(value: String) { _uiState.value = _uiState.value.copy(itemType = value) }
    fun updateKcal(value: String) { _uiState.value = _uiState.value.copy(kcal100g = value) }
    fun updateProtein(value: String) { _uiState.value = _uiState.value.copy(protein100g = value) }
    fun updateCarbs(value: String) { _uiState.value = _uiState.value.copy(carbs100g = value) }
    fun updateFat(value: String) { _uiState.value = _uiState.value.copy(fat100g = value) }
    fun updateFiber(value: String) { _uiState.value = _uiState.value.copy(fiber100g = value) }
    fun updateSugar(value: String) { _uiState.value = _uiState.value.copy(sugar100g = value) }
    fun updateSaturatedFat(value: String) { _uiState.value = _uiState.value.copy(saturatedFat100g = value) }
    fun updateSalt(value: String) { _uiState.value = _uiState.value.copy(saltG100g = value) }

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
                        // User-entered SALT (g) -> API expects sodium (mg):
                        // divide by the salt:sodium ratio, then by 1000
                        // for the unit change to mg... equivalently,
                        // multiply by 1000 and divide by the ratio.
                        sodiumMg100g = state.saltG100g.toDoubleOrNull()?.times(1000.0)?.div(SALT_TO_SODIUM_RATIO),
                        type = state.itemType,
                        origin = when {
                            state.usdaImportUsed -> "usda_import"
                            state.ocrWasUsed -> "ocr_assisted"
                            else -> "manual"
                        }
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