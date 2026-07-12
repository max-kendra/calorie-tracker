package com.mealtracker.android.network

import com.mealtracker.android.network.models.HealthResponse
import com.mealtracker.android.network.models.Item
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit turns this interface into real HTTP calls. Each function here
 * corresponds to one endpoint on the FastAPI backend (see the backend's
 * app/routers/*.py for what each one actually does server-side).
 *
 * Only /health and a basic item list/search are here for the skeleton --
 * add more functions here as we build out each screen, mirroring the
 * backend routers one at a time (items -> recipes -> logs -> ...).
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
}
