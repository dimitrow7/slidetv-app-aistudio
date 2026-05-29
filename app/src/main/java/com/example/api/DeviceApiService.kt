package com.example.api

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DeviceApiService {
    @POST("api/device/init")
    suspend fun init(@Header("X-Device-Token") token: String?): InitResponse

    @POST("api/device/poll")
    suspend fun poll(
        @Header("X-Device-Token") token: String,
        @Body body: PollRequestBody
    ): PollResponse
}
