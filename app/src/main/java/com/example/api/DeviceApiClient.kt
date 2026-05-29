package com.example.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object DeviceApiClient {
    private var service: DeviceApiService? = null
    private var currentBaseUrl = ""

    fun getService(baseUrl: String): DeviceApiService {
        val normalized = baseUrl.trimEnd('/') + '/'
        if (service == null || currentBaseUrl != normalized) {
            currentBaseUrl = normalized
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
            service = Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(DeviceApiService::class.java)
        }
        return service!!
    }
}
