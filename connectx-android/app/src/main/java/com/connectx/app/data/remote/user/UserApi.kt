package com.connectx.app.data.remote.user

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.auth.model.UserDto
import com.connectx.app.data.remote.user.model.PublicUserDto
import com.connectx.app.data.remote.user.model.RequestEmailChangeOtpRequest
import com.connectx.app.data.remote.user.model.UserIdentityKeyDto
import com.connectx.app.data.remote.user.model.UserProfileUpdateDto
import com.connectx.app.data.remote.user.model.VerifyEmailChangeOtpRequest
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Full Android-side contract for the backend's User API, as verified directly
 * against com.connectx.user.controller.UserController (10 endpoints) and
 * com.connectx.user.controller.ProfileImageController (1 endpoint) -- 11 total.
 *
 * The N2.0 inspection summary reported "User (10, incl. ProfileImageController)" --
 * a re-count directly from source during N2.2 found UserController alone has 10
 * @*Mapping methods, making 11 total with ProfileImageController. Recorded as a
 * discrepancy in docs/CONNECTX_ANDROID_DEVELOPMENT.md Section 29; this interface
 * reflects the verified source, not the earlier summary.
 *
 * UserDto is reused from data.remote.auth.model -- it is the exact same backend
 * class (com.connectx.user.dto.UserDto) returned by both auth and user endpoints,
 * so it is modeled once, not duplicated.
 *
 * Contract only -- no call site exists anywhere yet. Every one of these 11 endpoints
 * requires authentication on the backend (an authenticated UserPrincipal); no
 * Authorization header handling is added here -- that wiring is deferred to N3.
 */
interface UserApi {

    @GET("api/v1/users/search")
    suspend fun searchUsers(@Query("username") username: String): ApiResponse<List<PublicUserDto>>

    @GET("api/v1/users/me")
    suspend fun getCurrentUser(): ApiResponse<UserDto>

    @GET("api/v1/users/{userId}")
    suspend fun getUserById(@Path("userId") userId: Long): ApiResponse<PublicUserDto>

    @PATCH("api/v1/users/me")
    suspend fun updateProfile(@Body request: UserProfileUpdateDto): ApiResponse<UserDto>

    @POST("api/v1/users/me/email/request-otp")
    suspend fun requestEmailChangeOtp(@Body request: RequestEmailChangeOtpRequest): ApiResponse<String>

    @POST("api/v1/users/me/email/verify-otp")
    suspend fun verifyEmailChangeOtp(@Body request: VerifyEmailChangeOtpRequest): ApiResponse<UserDto>

    @Multipart
    @POST("api/v1/users/me/profile-photo")
    suspend fun uploadProfilePhoto(@Part file: MultipartBody.Part): ApiResponse<UserDto>

    @DELETE("api/v1/users/me/profile-photo")
    suspend fun removeProfilePhoto(): ApiResponse<UserDto>

    @GET("api/v1/users/me/identity-key")
    suspend fun getIdentityKey(): ApiResponse<UserIdentityKeyDto>

    @POST("api/v1/users/me/identity-key")
    suspend fun saveIdentityKey(@Body request: UserIdentityKeyDto): ApiResponse<UserIdentityKeyDto>

    /**
     * Raw binary endpoint (ProfileImageController#getProfileImage) -- returns image
     * bytes directly, never wrapped in ApiResponse. ResponseBody bypasses the
     * registered kotlinx.serialization converter automatically (Retrofit handles
     * ResponseBody natively). @Streaming avoids buffering the whole image in memory.
     */
    @Streaming
    @GET("api/v1/profile-images/{userId}")
    suspend fun getProfileImage(@Path("userId") userId: Long): ResponseBody
}
