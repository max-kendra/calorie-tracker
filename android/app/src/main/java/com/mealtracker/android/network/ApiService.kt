package com.mealtracker.android.network

import com.mealtracker.android.network.models.BarcodeScanResult
import com.mealtracker.android.network.models.Goal
import com.mealtracker.android.network.models.GoalCreateRequest
import com.mealtracker.android.network.models.GoalUpdateRequest
import com.mealtracker.android.network.models.HealthResponse
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.ItemCreateRequest
import com.mealtracker.android.network.models.KcalGoalCalculationResult
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.MealGoalSplitsUpdateRequest
import com.mealtracker.android.network.models.OcrScanResult
import com.mealtracker.android.network.models.UserProfile
import com.mealtracker.android.network.models.UserProfileUpdateRequest
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit turns this interface into real HTTP calls. Each function here
 * corresponds to one endpoint on the FastAPI backend (see the backend's
 * app/routers directory for what each one actually does server-side).
 */
interface ApiService {

    // Unauthenticated on the backend -- just confirms the API process is up.
    @GET("health")
    suspend fun getHealth(): HealthResponse

    // Backs the My Foods search/list (see backend: GET /items).
    @GET("items")
    suspend fun searchItems(
        @Query("q") query: String? = null,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int = 50
    ): List<Item>

    // Backs the Journal screen -- a single `date` gives one day's log
    // (matches the backend's GET /logs?date=... single-day mode).
    // Optional mealType narrows to just that meal (used by the Meal
    // Detail screen, which doesn't need the whole day's logs).
    @GET("logs")
    suspend fun getLogs(
        @Query("date") date: String,
        @Query("meal_type") mealType: String? = null
    ): List<Log>

    // The goal currently in effect (backend: GET /goals/active,
    // end_date IS NULL). Throws a 404 HttpException if none exists yet --
    // callers should catch that specifically to distinguish "no goal set
    // up yet" from a genuine error.
    @GET("goals/active")
    suspend fun getActiveGoal(): Goal

    // Creates the first goal, or a new one (auto-closes the previous
    // active goal server-side -- see backend app/routers/goals.py).
    @POST("goals")
    suspend fun createGoal(@Body request: GoalCreateRequest): Goal

    // Updates an existing goal's targets in place.
    @PATCH("goals/{goalId}")
    suspend fun updateGoal(@Path("goalId") goalId: Int, @Body request: GoalUpdateRequest): Goal

    // Bulk-replaces all of a goal's meal splits at once (backend enforces
    // they must sum to exactly 100% -- see app/routers/goals.py).
    @PUT("goals/{goalId}/meal-splits")
    suspend fun updateMealSplits(
        @Path("goalId") goalId: Int,
        @Body request: MealGoalSplitsUpdateRequest
    ): Goal

    // Single-user profile -- auto-created on first access (backend:
    // GET /profile).
    @GET("profile")
    suspend fun getProfile(): UserProfile

    @PATCH("profile")
    suspend fun updateProfile(@Body request: UserProfileUpdateRequest): UserProfile

    // Reads height/age/weight/hormone/activity_level/goal_type from the
    // STORED profile -- no request body. Throws 400 (HttpException) if
    // required profile fields are missing.
    @POST("profile/calculate-kcal-goal")
    suspend fun calculateKcalGoal(): KcalGoalCalculationResult

    // Uploads an image, decodes a barcode from it (pyzbar + zxing-cpp
    // fallback, see backend). NEVER auto-creates an item -- caller must
    // show `barcode` to the user for confirmation before using it (see
    // BarcodeScanResult's doc comment for why: real-world testing found
    // decoders can return a wrong value that still looks valid).
    @Multipart
    @POST("items/scan-barcode")
    suspend fun scanBarcode(@Part image: MultipartBody.Part): BarcodeScanResult

    // Uploads a nutrition label photo, OCR-extracts macros (Tesseract,
    // 9 languages, see backend). NEVER writes to the DB -- caller
    // pre-fills the Add Item form with the result for user review.
    @Multipart
    @POST("items/scan-label")
    suspend fun scanLabel(@Part image: MultipartBody.Part): OcrScanResult

    // Creates a new item. 409 (HttpException) if the barcode is already
    // in use by another item.
    @POST("items")
    suspend fun createItem(@Body request: ItemCreateRequest): Item

    // Looks up an item by barcode directly -- used before scanning, to
    // check if a barcode already has a matching item (404 if not).
    @GET("items/barcode/{barcode}")
    suspend fun getItemByBarcode(@Path("barcode") barcode: String): Item
}
