package com.mealtracker.android.network

import com.mealtracker.android.network.models.Goal
import com.mealtracker.android.network.models.GoalCreateRequest
import com.mealtracker.android.network.models.GoalUpdateRequest
import com.mealtracker.android.network.models.HealthResponse
import com.mealtracker.android.network.models.Item
import com.mealtracker.android.network.models.Log
import com.mealtracker.android.network.models.MealGoalSplitsUpdateRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit turns this interface into real HTTP calls. Each function here
 * corresponds to one endpoint on the FastAPI backend (see the backend's
 * app/routers directory for what each one actually does server-side).
 *
 * Add more functions as we build out each screen, mirroring the backend
 * routers one at a time (items -> recipes -> logs -> goals -> ...).
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
    @GET("logs")
    suspend fun getLogs(@Query("date") date: String): List<Log>

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
}
