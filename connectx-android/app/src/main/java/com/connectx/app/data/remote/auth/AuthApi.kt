package com.connectx.app.data.remote.auth

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Minimum Retrofit contract for the two core authentication endpoints
 * (com.connectx.auth.controller.AuthController#register / #login).
 * No path leads with "/" since BuildConfig.BASE_URL already ends in "/".
 *
 * This is a contract only for N2.1 -- no call site exists anywhere yet.
 * refresh/logout/forgot-password endpoints and all actual authentication
 * behavior (token storage, interceptors, auth state) are deferred to N3.
 */
interface AuthApi {

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<AuthResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<AuthResponse>
}
