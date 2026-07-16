package com.mealtracker.android.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mealtracker.android.health.HealthConnectManager
import com.mealtracker.android.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How far back the weight graph/list looks -- the range-selector buttons
 * on the Profile screen. Durations are fixed-day approximations (e.g.
 * "1 month" = 30 days, not calendar-month-aware) -- close enough for a
 * trend graph and much simpler than calendar arithmetic; not meant to be
 * exact billing-cycle-style ranges.
 */
enum class WeightRange(val label: String, val days: Long) {
    WEEK("1W", 7),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 182),
    YEAR("1Y", 365)
}

data class ProfileOverviewUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,

    val name: String? = null,
    val profilePicPath: String? = null,

    // Weight goal summary -- see UserProfile model docstring (backend)
    // for why starting/goal are fixed manual reference points while
    // "current" always comes from Health Connect, not a stored column.
    val startingWeightKg: Double? = null,
    val goalWeightKg: Double? = null,
    // The weight the user last entered on the Calorie Goal screen for
    // its TDEE calculation (profile.weight_kg on the backend) -- used
    // as a fallback for currentWeightKg below when Health Connect has
    // no reading, so "current weight" on this screen isn't ONLY ever
    // sourced from Health Connect. Health Connect still wins when both
    // exist, since it's the more likely to be fresh/accurate of the two
    // (a live reading vs. whatever was typed in during goal setup,
    // possibly a while ago).
    val profileWeightKg: Double? = null,

    val isUploadingPicture: Boolean = false,
    val pictureError: String? = null,

    // Health Connect state -- distinct "not available on this device"
    // vs "available but we don't have permission yet" vs "granted,
    // here's the data" so the UI can show the right prompt for each.
    val healthConnectAvailable: Boolean = false,
    val healthConnectPermissionGranted: Boolean = false,
    val selectedRange: WeightRange = WeightRange.MONTH,
    val weightHistory: List<HealthConnectManager.WeightEntry> = emptyList(),
    val isLoadingWeights: Boolean = false,
    val weightsError: String? = null
) {
    val currentWeightKg: Double? get() = weightHistory.maxByOrNull { it.time }?.kg ?: profileWeightKg
    val weightDiffFromGoalKg: Double?
        get() {
            val current = currentWeightKg ?: return null
            val goal = goalWeightKg ?: return null
            return current - goal
        }
}

class ProfileOverviewViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileOverviewUiState())
    val uiState: StateFlow<ProfileOverviewUiState> = _uiState

    /** Called once from the Composable (e.g. in a LaunchedEffect(Unit))
     * with an application Context -- checks Health Connect availability/
     * permission state and loads both the backend profile and (if
     * permitted) the initial weight history. */
    fun initialize(context: Context) {
        loadProfile()
        refreshHealthConnectState(context)
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val profile = ApiClient.service.getProfile()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    name = profile.name,
                    profilePicPath = profile.profilePicPath,
                    startingWeightKg = profile.startingWeightKg?.toDoubleOrNull(),
                    goalWeightKg = profile.goalWeightKg?.toDoubleOrNull(),
                    profileWeightKg = profile.weightKg?.toDoubleOrNull()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadError = e.message ?: "Unknown error"
                )
            }
        }
    }

    /** Re-checks availability/permission -- call this again after
     * returning from the Health Connect permission prompt (its result
     * arrives async via the ActivityResultContract in the Composable,
     * which should call this to refresh afterward regardless of
     * whether the user granted or denied). */
    fun refreshHealthConnectState(context: Context) {
        val appContext = context.applicationContext
        val available = HealthConnectManager.isAvailable(appContext)
        _uiState.value = _uiState.value.copy(healthConnectAvailable = available)
        if (!available) return

        viewModelScope.launch {
            val granted = HealthConnectManager.hasAllPermissions(appContext)
            _uiState.value = _uiState.value.copy(healthConnectPermissionGranted = granted)
            if (granted) {
                loadWeightHistory(appContext)
            }
        }
    }

    fun selectRange(context: Context, range: WeightRange) {
        _uiState.value = _uiState.value.copy(selectedRange = range)
        loadWeightHistory(context.applicationContext)
    }

    fun uploadProfilePicture(imageBytes: ByteArray) {
        _uiState.value = _uiState.value.copy(isUploadingPicture = true, pictureError = null)
        viewModelScope.launch {
            try {
                val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("image", "profile.jpg", requestBody)
                val updated = ApiClient.service.uploadProfilePicture(part)
                _uiState.value = _uiState.value.copy(
                    isUploadingPicture = false,
                    profilePicPath = updated.profilePicPath
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingPicture = false,
                    pictureError = e.message ?: "Failed to upload picture"
                )
            }
        }
    }

    private fun loadWeightHistory(appContext: Context) {
        val range = _uiState.value.selectedRange
        _uiState.value = _uiState.value.copy(isLoadingWeights = true, weightsError = null)

        viewModelScope.launch {
            try {
                val end = Instant.now()
                val start = end.minus(range.days, ChronoUnit.DAYS)
                val history = HealthConnectManager.readWeightHistory(appContext, start, end)
                _uiState.value = _uiState.value.copy(isLoadingWeights = false, weightHistory = history)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingWeights = false,
                    weightsError = e.message ?: "Couldn't load weight history"
                )
            }
        }
    }
}