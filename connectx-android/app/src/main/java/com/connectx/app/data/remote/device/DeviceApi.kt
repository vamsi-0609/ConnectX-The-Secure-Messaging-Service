package com.connectx.app.data.remote.device

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.device.model.DeviceResponseDto
import com.connectx.app.data.remote.device.model.RegisterDeviceDto
import com.connectx.app.data.remote.device.model.UserPublicKeyDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Full Android-side contract for the backend's Device API, verified directly
 * against com.connectx.device.controller.DeviceController -- 5 endpoints,
 * matching the N2.0 inspection's reported count exactly (no discrepancy this
 * time; only N2.2's User and N2.5's Message groups were undercounted).
 *
 * getUserPublicKeys is a device endpoint (path /users/{userId}/devices/public-keys
 * lives on DeviceController, not UserController) -- kept here, not in UserApi, to
 * mirror the actual backend controller boundary.
 *
 * Contract only -- no call site exists anywhere yet, no FCM/token registration
 * behavior, no device management UI. All 5 endpoints require authentication on
 * the backend; no Authorization header handling is added here (deferred to N3).
 * No cryptographic behavior implemented -- publicKey/keyAlgorithm are opaque
 * scalar fields only (N7's boundary).
 */
interface DeviceApi {

    @POST("api/v1/devices")
    suspend fun registerDevice(@Body request: RegisterDeviceDto): ApiResponse<DeviceResponseDto>

    @GET("api/v1/devices")
    suspend fun getMyDevices(): ApiResponse<List<DeviceResponseDto>>

    @DELETE("api/v1/devices/{deviceId}")
    suspend fun deactivateDevice(@Path("deviceId") deviceId: Long): ApiResponse<String>

    @POST("api/v1/devices/{deviceId}/seen")
    suspend fun markDeviceSeen(@Path("deviceId") deviceId: Long): ApiResponse<DeviceResponseDto>

    @GET("api/v1/users/{userId}/devices/public-keys")
    suspend fun getUserPublicKeys(@Path("userId") userId: Long): ApiResponse<List<UserPublicKeyDto>>
}
