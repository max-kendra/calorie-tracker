package com.mealtracker.android.network

import com.mealtracker.android.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Single shared Retrofit/OkHttp instance for the whole app. Simple
 * object (singleton) rather than a DI framework (Hilt etc.) -- fine for
 * a skeleton/personal app; revisit if this grows enough to want proper
 * dependency injection.
 */
object ApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    // Adds the X-API-Key header to EVERY request automatically, so
    // individual API calls never need to think about auth -- matches
    // the backend's require_api_key dependency on every router.
    private val authInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", BuildConfig.API_KEY)
            .build()
        chain.proceed(request)
    }

    // Logs full request/response bodies to Logcat in debug builds --
    // extremely useful while wiring up new screens, but never enable
    // this in a release build (would leak your API key to logs).
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}
