package com.mealtracker.android.network

import com.mealtracker.android.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Single shared Retrofit/OkHttp instance for the whole app. Simple
 * object (singleton) rather than a DI framework (Hilt etc.) - fine for
 * a skeleton/personal app; revisit if this grows enough to want proper
 * dependency injection.
 */
object ApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    // Adds the X-API-Key header to EVERY request automatically, so
    // individual API calls never need to think about auth - matches
    // the backend's require_api_key dependency on every router.
    private val authInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", BuildConfig.API_KEY)
            .build()
        chain.proceed(request)
    }

    // Logs full request/response bodies to Logcat in debug builds -
    // extremely useful while wiring up new screens, but never enable
    // this in a release build (would leak your API key to logs).
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    // Default OkHttp timeouts (10s connect/read/write) were almost
    // certainly why OCR requests kept "timing out" even after the
    // server-side download/warm-up issues were fixed - EasyOCR
    // inference on a Pi's CPU (no GPU) genuinely can take 15-40+ seconds
    // per label photo (confirmed: the server logs showed these requests
    // completing successfully with real results, just slowly - the
    // client was giving up before the response ever arrived). Read
    // timeout raised generously to cover that; connect/write stay
    // closer to default since those aren't the slow part.
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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