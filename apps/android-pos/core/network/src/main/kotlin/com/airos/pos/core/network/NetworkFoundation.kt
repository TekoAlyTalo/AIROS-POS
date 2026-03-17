package com.airos.pos.core.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

data class NetworkEnvironment(
    val baseUrl: String,
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 20,
)

interface AirosPosApi {
    // TODO-CONTRACT: Define backend endpoints once the Android POS contract is finalized.
}

object NetworkFoundation {
    fun createHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .build()
    }

    fun createRetrofit(environment: NetworkEnvironment): Retrofit {
        return Retrofit.Builder()
            .baseUrl(environment.baseUrl)
            .client(createHttpClient())
            .build()
    }

    fun createApi(environment: NetworkEnvironment): AirosPosApi {
        return createRetrofit(environment).create(AirosPosApi::class.java)
    }
}
