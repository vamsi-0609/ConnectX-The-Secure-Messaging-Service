package com.connectx.app.data.remote.connection

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.connection.model.ConnectionRequestDto
import com.connectx.app.data.remote.connection.model.SendConnectionRequestDto
import com.connectx.app.data.remote.connection.model.UserConnectionDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Full Android-side contract for the backend's Connection API, verified directly
 * against com.connectx.connection.controller.ConnectionController -- 8 endpoints,
 * matching the N2.0 inspection's reported count exactly (no discrepancy this time).
 *
 * Contract only -- no call site exists anywhere yet. All 8 endpoints require
 * authentication on the backend; no Authorization header handling is added here
 * (deferred to N3).
 */
interface ConnectionApi {

    @POST("api/v1/connections/requests")
    suspend fun sendRequest(@Body request: SendConnectionRequestDto): ApiResponse<ConnectionRequestDto>

    @GET("api/v1/connections/requests/pending")
    suspend fun getPendingIncomingRequests(): ApiResponse<List<ConnectionRequestDto>>

    @GET("api/v1/connections/requests/sent")
    suspend fun getSentOutgoingRequests(): ApiResponse<List<ConnectionRequestDto>>

    @POST("api/v1/connections/requests/{id}/accept")
    suspend fun acceptRequest(@Path("id") requestId: Long): ApiResponse<ConnectionRequestDto>

    @POST("api/v1/connections/requests/{id}/reject")
    suspend fun rejectRequest(@Path("id") requestId: Long): ApiResponse<ConnectionRequestDto>

    @POST("api/v1/connections/requests/{id}/cancel")
    suspend fun cancelRequest(@Path("id") requestId: Long): ApiResponse<ConnectionRequestDto>

    @GET("api/v1/connections")
    suspend fun getConnections(): ApiResponse<List<UserConnectionDto>>

    @DELETE("api/v1/connections/{userId}")
    suspend fun removeConnection(@Path("userId") userId: Long): ApiResponse<String>
}
