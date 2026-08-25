package com.connectx.app.data.remote.block

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.block.model.UserBlockDto
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Full Android-side contract for the backend's Block API, verified directly
 * against com.connectx.block.controller.BlockController -- 3 endpoints, matching
 * the N2.0 inspection's reported count exactly (no discrepancy this time).
 *
 * Kept as its own interface (not merged into ConnectionApi) since the backend
 * exposes it as a fully separate controller/domain (/api/v1/blocks, not nested
 * under /api/v1/connections).
 *
 * Contract only -- no call site exists anywhere yet. All 3 endpoints require
 * authentication on the backend; no Authorization header handling is added here
 * (deferred to N3).
 */
interface BlockApi {

    @POST("api/v1/blocks/{userId}")
    suspend fun blockUser(@Path("userId") userId: Long): ApiResponse<UserBlockDto>

    @DELETE("api/v1/blocks/{userId}")
    suspend fun unblockUser(@Path("userId") userId: Long): ApiResponse<String>

    @GET("api/v1/blocks")
    suspend fun getMyBlocks(): ApiResponse<List<UserBlockDto>>
}
