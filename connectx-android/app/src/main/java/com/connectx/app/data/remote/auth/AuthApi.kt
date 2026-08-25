package com.connectx.app.data.remote.auth

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.ForgotPasswordRequestDto
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RefreshTokenRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import com.connectx.app.data.remote.auth.model.ResetPasswordRequestDto
import com.connectx.app.data.remote.auth.model.VerifyOtpRequestDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit contract for the full `com.connectx.auth.controller.AuthController`
 * domain: register/login/refresh plus, as of N3.6, the three email-based
 * account-recovery endpoints. `logout` remains deliberately unmodeled -- it
 * is local-only (see `AuthRepository.logout`), never a network call. No path
 * leads with "/" since BuildConfig.BASE_URL already ends in "/".
 *
 * All six modeled endpoints are bound exclusively to the UNAUTHENTICATED
 * Retrofit/OkHttpClient instance in `NetworkModule` (no `AuthInterceptor`,
 * no `TokenAuthenticator` attached) -- every one of them is `permitAll()` on
 * the backend, and keeping the whole of `AuthApi` off the authenticated
 * client is what makes a bad-credentials 401 from `login` (and the refresh
 * call itself) structurally incapable of ever reaching `TokenAuthenticator`.
 * See `NetworkModule`/docs Section 38 for the full two-client rationale.
 *
 * The three recovery endpoints are email-based, matching ConnectX's actual
 * identity model exactly (username is for discovery/login; email is for
 * account recovery) -- no phone-number contract exists on the backend, so
 * none is modeled here.
 */
interface AuthApi {

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<AuthResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<AuthResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshTokenRequest): ApiResponse<AuthResponse>

    @POST("api/v1/auth/forgot-password/request-otp")
    suspend fun requestForgotPasswordOtp(@Body request: ForgotPasswordRequestDto): ApiResponse<String>

    @POST("api/v1/auth/forgot-password/verify-otp")
    suspend fun verifyForgotPasswordOtp(@Body request: VerifyOtpRequestDto): ApiResponse<String>

    @POST("api/v1/auth/forgot-password/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequestDto): ApiResponse<String>
}
