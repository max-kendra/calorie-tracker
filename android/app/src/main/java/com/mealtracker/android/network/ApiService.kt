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
import com.mealtracker.android.network.models.LogCreateRequest
import com.mealtracker.android.network.models.LogUpdateRequest
import com.mealtracker.android.network.models.MealGoalSplitsUpdateRequest
import com.mealtracker.android.network.models.OcrScanResult
import com.mealtracker.android.network.models.ProductPhotoScanResult
import com.mealtracker.android.network.models.Recipe
import com.mealtracker.android.network.models.RecipeCreateRequest
import com.mealtracker.android.network.models.UsdaFoodDetail
import com.mealtracker.android.network.models.UsdaFoodSummary
import com.mealtracker.android.network.models.UserProfile
import com.mealtracker.android.network.models.UserProfileUpdateRequest
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
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

    // Used by the Journal screen's calendar picker to color each day by
    // how many distinct meal types were logged that day -- one call for
    // the whole visible month rather than 28-31 individual per-day calls.
    @GET("logs")
    suspend fun getLogsInRange(
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String
    ): List<Log>

    // Backs the Add Item sheet's default "Saved" tab -- items sorted by
    // most recently LOGGED, not most recently added to the catalog (see
    // backend docstring). Recipes are not included, Items only.
    @GET("logs/recent-items")
    suspend fun getRecentItems(
        @Query("meal_type") mealType: String? = null,
        @Query("limit") limit: Int = 20
    ): List<Item>

    // Creates a log entry -- the actual "add this item to this meal"
    // action. See LogCreateRequest's doc comment for the current 100g
    // flat-default quantity simplification.
    @POST("logs")
    suspend fun createLog(@Body request: LogCreateRequest): Log

    // Quantity-only edit -- backs the log detail/edit screen.
    @PATCH("logs/{logId}")
    suspend fun updateLog(@Path("logId") logId: Int, @Body request: LogUpdateRequest): Log

    @DELETE("logs/{logId}")
    suspend fun deleteLog(@Path("logId") logId: Int)

    // Raw-ingredient lookup -- backs the "is this a raw ingredient?"
    // path in AddItemViewModel (see NEW_ITEM_TYPE_PROMPT/USDA_SEARCH
    // phases). Defaults to Foundation+SR Legacy data types server-side
    // (lab-analyzed, stable), not Branded.
    @GET("usda/search")
    suspend fun searchUsda(
        @Query("query") query: String,
        @Query("page_size") pageSize: Int = 15
    ): List<UsdaFoodSummary>

    @GET("usda/food/{fdcId}")
    suspend fun getUsdaFood(@Path("fdcId") fdcId: Int): UsdaFoodDetail

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

    // Saves a (client-cropped) profile picture and sets it as the
    // current profile's picture in one step -- returns the updated
    // profile (with the new profile_pic_path) directly, unlike
    // scanProductPhoto/scanLabel which just return a draft for the
    // caller to decide what to do with.
    @Multipart
    @POST("profile/picture")
    suspend fun uploadProfilePicture(@Part image: MultipartBody.Part): UserProfile

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

    // Uploads a (client-cropped) photo of the product package itself --
    // step 2 of the Add Item flow, between barcode scan and nutrition
    // label scan. Saves the image server-side (see backend) and returns
    // its path for the client to attach to the item on save, plus a
    // best-effort guessed name/brand from OCR -- NEVER writes to the DB
    // itself, and the guesses must be shown as editable, not assumed
    // correct (see ProductPhotoScanResult's doc comment).
    @Multipart
    @POST("items/scan-product-photo")
    suspend fun scanProductPhoto(@Part image: MultipartBody.Part): ProductPhotoScanResult

    // Uploads a nutrition label photo, OCR-extracts macros (EasyOCR,
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

    // Used by the "star icon: save this meal" feature -- a saved Meal is
    // just a Recipe with recipe_type="meal" and servings=1.
    @POST("recipes")
    suspend fun createRecipe(@Body request: RecipeCreateRequest): Recipe
}