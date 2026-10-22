package com.mlbb.counteroverlay.data.remote

import com.mlbb.counteroverlay.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true   // tolerate new server fields
        coerceInputValues = true   // null for non-null field -> default
        isLenient = true
    }

    private val okHttp: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            // HEADERS keeps Logcat readable; switch to BODY when debugging the JSON itself.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
            else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "MlbbCounterOverlay/${BuildConfig.VERSION_NAME}")
                        .header("Accept", "application/json")
                        .build()
                )
            }
            .addInterceptor(logging)
            .build()
    }

    val api: MlbbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttp)
            .build()
            .create(MlbbApi::class.java)
    }
}
