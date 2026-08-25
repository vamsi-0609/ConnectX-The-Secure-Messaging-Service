package com.connectx.app.data.remote.auth

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.core.session.SessionManager
import com.connectx.app.core.session.SessionState
import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.ForgotPasswordRequestDto
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RefreshTokenRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import com.connectx.app.data.remote.auth.model.ResetPasswordRequestDto
import com.connectx.app.data.remote.auth.model.UserDto
import com.connectx.app.data.remote.auth.model.VerifyOtpRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * JVM-only verification of AuthRepositoryImpl's register/login/logout
 * behavior, using hand-written in-memory fakes for [AuthApi] and
 * [TokenStorage] (no mocking framework, no real network, no real Retrofit/
 * OkHttp call). A real [SessionManager] is used on top of the fake storage --
 * it's plain Kotlin logic already covered on its own in
 * `SessionManagerTest`, and using the real class here is what actually proves
 * the repository -> SessionManager wiring behaves correctly end to end.
 */
class AuthRepositoryImplTest {

    private class FakeTokenStorage : TokenStorage {
        private var accessToken: String? = null
        private var refreshToken: String? = null
        var throwOnSave: Boolean = false

        override fun saveTokens(accessToken: String, refreshToken: String) {
            if (throwOnSave) throw IllegalStateException("simulated persistence failure")
            this.accessToken = accessToken
            this.refreshToken = refreshToken
        }

        override fun getAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = refreshToken
        override fun getTokens(): TokenPair? {
            val access = accessToken ?: return null
            val refresh = refreshToken ?: return null
            return TokenPair(access, refresh)
        }

        override fun clearTokens() {
            accessToken = null
            refreshToken = null
        }

        override fun hasTokens(): Boolean = getTokens() != null
    }

    private class FakeAuthApi : AuthApi {
        var registerAction: (suspend () -> ApiResponse<AuthResponse>)? = null
        var loginAction: (suspend () -> ApiResponse<AuthResponse>)? = null
        var refreshAction: (suspend () -> ApiResponse<AuthResponse>)? = null
        var requestOtpAction: (suspend () -> ApiResponse<String>)? = null
        var verifyOtpAction: (suspend () -> ApiResponse<String>)? = null
        var resetPasswordAction: (suspend () -> ApiResponse<String>)? = null

        var lastForgotPasswordRequest: ForgotPasswordRequestDto? = null
        var lastVerifyOtpRequest: VerifyOtpRequestDto? = null
        var lastResetPasswordRequest: ResetPasswordRequestDto? = null

        override suspend fun register(request: RegisterRequest): ApiResponse<AuthResponse> =
            registerAction?.invoke() ?: error("registerAction not configured for this test")

        override suspend fun login(request: LoginRequest): ApiResponse<AuthResponse> =
            loginAction?.invoke() ?: error("loginAction not configured for this test")

        override suspend fun refresh(request: RefreshTokenRequest): ApiResponse<AuthResponse> =
            refreshAction?.invoke() ?: error("refreshAction not configured for this test")

        override suspend fun requestForgotPasswordOtp(request: ForgotPasswordRequestDto): ApiResponse<String> {
            lastForgotPasswordRequest = request
            return requestOtpAction?.invoke() ?: error("requestOtpAction not configured for this test")
        }

        override suspend fun verifyForgotPasswordOtp(request: VerifyOtpRequestDto): ApiResponse<String> {
            lastVerifyOtpRequest = request
            return verifyOtpAction?.invoke() ?: error("verifyOtpAction not configured for this test")
        }

        override suspend fun resetPassword(request: ResetPasswordRequestDto): ApiResponse<String> {
            lastResetPasswordRequest = request
            return resetPasswordAction?.invoke() ?: error("resetPasswordAction not configured for this test")
        }
    }

    private fun realUser() = UserDto(
        id = 7L,
        username = "alice",
        email = "alice@example.com",
        displayName = "Alice"
    )

    private fun successResponse(accessToken: String = "access-123", refreshToken: String = "refresh-456") =
        ApiResponse(
            success = true,
            code = "SUCCESS",
            message = "Login successful",
            data = AuthResponse(accessToken = accessToken, refreshToken = refreshToken, user = realUser())
        )

    private class Fixture {
        val tokenStorage = FakeTokenStorage()
        val sessionManager = SessionManager(tokenStorage)
        val authApi = FakeAuthApi()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val repository: AuthRepository = AuthRepositoryImpl(authApi, sessionManager, json)
    }

    @Test
    fun `successful login persists tokens and authenticates the session`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse() }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.Success)
        assertEquals("alice", (result as AuthResult.Success).user.username)
        assertEquals("access-123", fx.tokenStorage.getAccessToken())
        assertEquals("refresh-456", fx.tokenStorage.getRefreshToken())
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `successful register persists tokens and authenticates the session`() = runBlocking {
        val fx = Fixture()
        fx.authApi.registerAction = { successResponse(accessToken = "reg-access", refreshToken = "reg-refresh") }

        val result = fx.repository.register(RegisterRequest("bob", "bob@example.com", "password123"))

        assertTrue(result is AuthResult.Success)
        assertEquals("reg-access", fx.tokenStorage.getAccessToken())
        assertEquals("reg-refresh", fx.tokenStorage.getRefreshToken())
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `token persistence failure does not authenticate the session`() = runBlocking {
        val fx = Fixture()
        fx.tokenStorage.throwOnSave = true
        fx.authApi.loginAction = { successResponse() }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.UnexpectedError)
        assertNull(fx.tokenStorage.getAccessToken())
        assertNull(fx.sessionManager.sessionState.value.let { it as? SessionState.Authenticated })
    }

    @Test
    fun `missing access token is rejected and never persisted`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse(accessToken = "") }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.InvalidResponse)
        assertNull(fx.tokenStorage.getAccessToken())
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `missing refresh token is rejected and never persisted`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse(refreshToken = "") }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.InvalidResponse)
        assertNull(fx.tokenStorage.getRefreshToken())
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `a backend success=false envelope is treated as an invalid response, not a Success`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = {
            ApiResponse(success = false, code = "SOMETHING", message = "not actually ok", data = null)
        }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.InvalidResponse)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `backend API error (HttpException) is returned as ApiError with parsed ErrorResponse fields`() = runBlocking {
        val fx = Fixture()
        val errorJson = """
            {"timestamp":"2026-08-25T10:00:00Z","status":401,"code":"UNAUTHORIZED","message":"Bad credentials","path":"/api/v1/auth/login"}
        """.trimIndent()
        val httpException = HttpException(
            Response.error<AuthResponse>(401, errorJson.toResponseBody("application/json".toMediaType()))
        )
        fx.authApi.loginAction = { throw httpException }

        val result = fx.repository.login(LoginRequest("alice", "wrong-password"))

        assertTrue(result is AuthResult.ApiError)
        val apiError = result as AuthResult.ApiError
        assertEquals(401, apiError.status)
        assertEquals("UNAUTHORIZED", apiError.code)
        assertEquals("Bad credentials", apiError.message)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `a network failure (IOException) is returned as NetworkError`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { throw IOException("Unable to resolve host") }

        val result = fx.repository.login(LoginRequest("alice", "password123"))

        assertTrue(result is AuthResult.NetworkError)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `successful refresh persists a rotated token pair and stays authenticated`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse() }
        fx.repository.login(LoginRequest("alice", "password123"))
        assertEquals("access-123", fx.tokenStorage.getAccessToken())

        fx.authApi.refreshAction = { successResponse(accessToken = "rotated-access", refreshToken = "rotated-refresh") }
        val result = fx.repository.refresh("refresh-456")

        assertTrue(result is AuthResult.Success)
        assertEquals("rotated-access", fx.tokenStorage.getAccessToken())
        assertEquals("rotated-refresh", fx.tokenStorage.getRefreshToken())
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `refresh rejected by the backend is returned as ApiError and never persisted`() = runBlocking {
        val fx = Fixture()
        val errorJson = """
            {"timestamp":"2026-08-25T10:00:00Z","status":401,"code":"INVALID_REFRESH_TOKEN","message":"Refresh token is invalid or expired","path":"/api/v1/auth/refresh"}
        """.trimIndent()
        fx.authApi.refreshAction = {
            throw HttpException(Response.error<AuthResponse>(401, errorJson.toResponseBody("application/json".toMediaType())))
        }

        val result = fx.repository.refresh("expired-refresh-token")

        assertTrue(result is AuthResult.ApiError)
        assertEquals("INVALID_REFRESH_TOKEN", (result as AuthResult.ApiError).code)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `logout clears tokens and transitions the session to Unauthenticated`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse() }
        fx.repository.login(LoginRequest("alice", "password123"))
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)

        fx.repository.logout()

        assertEquals(SessionState.Unauthenticated, fx.sessionManager.sessionState.value)
        assertNull(fx.tokenStorage.getAccessToken())
        assertNull(fx.tokenStorage.getRefreshToken())
    }

    // ---- N3.6: account recovery (request OTP / verify OTP / reset password) ----
    // None of these three operations ever touch SessionManager -- the backend
    // returns a plain confirmation message (ApiResponse<String>), never an
    // AuthResponse/token pair, for any of them.

    @Test
    fun `requesting a password reset OTP succeeds without touching the session`() = runBlocking {
        val fx = Fixture()
        fx.authApi.requestOtpAction = {
            ApiResponse(success = true, message = "OTP sent", data = "OTP sent")
        }

        val result = fx.repository.requestPasswordResetOtp("alice@example.com")

        assertTrue(result is AccountRecoveryResult.Success)
        assertEquals("OTP sent", (result as AccountRecoveryResult.Success).message)
        assertEquals("alice@example.com", fx.authApi.lastForgotPasswordRequest?.email)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `requesting a password reset OTP surfaces a backend API error (e_g_ rate limited)`() = runBlocking {
        val fx = Fixture()
        val errorJson = """
            {"timestamp":"2026-08-25T10:00:00Z","status":429,"code":"OTP_RATE_LIMITED","message":"Please wait a moment before requesting another code.","path":"/api/v1/auth/forgot-password/request-otp"}
        """.trimIndent()
        fx.authApi.requestOtpAction = {
            throw HttpException(Response.error<String>(429, errorJson.toResponseBody("application/json".toMediaType())))
        }

        val result = fx.repository.requestPasswordResetOtp("alice@example.com")

        assertTrue(result is AccountRecoveryResult.ApiError)
        val apiError = result as AccountRecoveryResult.ApiError
        assertEquals(429, apiError.status)
        assertEquals("OTP_RATE_LIMITED", apiError.code)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `requesting a password reset OTP surfaces a network failure`() = runBlocking {
        val fx = Fixture()
        fx.authApi.requestOtpAction = { throw IOException("Unable to resolve host") }

        val result = fx.repository.requestPasswordResetOtp("alice@example.com")

        assertTrue(result is AccountRecoveryResult.NetworkError)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `verifying an OTP succeeds without touching the session`() = runBlocking {
        val fx = Fixture()
        fx.authApi.verifyOtpAction = {
            ApiResponse(success = true, message = "OTP verified", data = "OTP verified")
        }

        val result = fx.repository.verifyPasswordResetOtp("alice@example.com", "123456")

        assertTrue(result is AccountRecoveryResult.Success)
        assertEquals("alice@example.com", fx.authApi.lastVerifyOtpRequest?.email)
        assertEquals("123456", fx.authApi.lastVerifyOtpRequest?.otpCode)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `verifying an invalid OTP surfaces a backend API error and does not touch the session`() = runBlocking {
        val fx = Fixture()
        val errorJson = """
            {"timestamp":"2026-08-25T10:00:00Z","status":400,"code":"INVALID_OTP","message":"Incorrect verification code. Please try again.","path":"/api/v1/auth/forgot-password/verify-otp"}
        """.trimIndent()
        fx.authApi.verifyOtpAction = {
            throw HttpException(Response.error<String>(400, errorJson.toResponseBody("application/json".toMediaType())))
        }

        val result = fx.repository.verifyPasswordResetOtp("alice@example.com", "000000")

        assertTrue(result is AccountRecoveryResult.ApiError)
        assertEquals("INVALID_OTP", (result as AccountRecoveryResult.ApiError).code)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `verifying an OTP surfaces a network failure`() = runBlocking {
        val fx = Fixture()
        fx.authApi.verifyOtpAction = { throw IOException("timeout") }

        val result = fx.repository.verifyPasswordResetOtp("alice@example.com", "123456")

        assertTrue(result is AccountRecoveryResult.NetworkError)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `resetting the password succeeds without authenticating the session`() = runBlocking {
        val fx = Fixture()
        fx.authApi.resetPasswordAction = {
            ApiResponse(success = true, message = "Password reset", data = "Password reset")
        }

        val result = fx.repository.resetPassword("alice@example.com", "123456", "new-password-123")

        assertTrue(result is AccountRecoveryResult.Success)
        assertEquals("alice@example.com", fx.authApi.lastResetPasswordRequest?.email)
        assertEquals("123456", fx.authApi.lastResetPasswordRequest?.otpCode)
        assertEquals("new-password-123", fx.authApi.lastResetPasswordRequest?.newPassword)
        // Resetting a password never authenticates -- confirmed unchanged from Unknown,
        // matching the backend's own "you can now login with your new password" semantics.
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
        assertNull(fx.tokenStorage.getAccessToken())
    }

    @Test
    fun `resetting the password surfaces a backend API error and does not authenticate`() = runBlocking {
        val fx = Fixture()
        val errorJson = """
            {"timestamp":"2026-08-25T10:00:00Z","status":400,"code":"EXPIRED_OTP","message":"Verification code has expired. Please request a new code.","path":"/api/v1/auth/forgot-password/reset-password"}
        """.trimIndent()
        fx.authApi.resetPasswordAction = {
            throw HttpException(Response.error<String>(400, errorJson.toResponseBody("application/json".toMediaType())))
        }

        val result = fx.repository.resetPassword("alice@example.com", "123456", "new-password-123")

        assertTrue(result is AccountRecoveryResult.ApiError)
        assertEquals("EXPIRED_OTP", (result as AccountRecoveryResult.ApiError).code)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `resetting the password surfaces a network failure`() = runBlocking {
        val fx = Fixture()
        fx.authApi.resetPasswordAction = { throw IOException("connection refused") }

        val result = fx.repository.resetPassword("alice@example.com", "123456", "new-password-123")

        assertTrue(result is AccountRecoveryResult.NetworkError)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `a malformed recovery response envelope is treated as InvalidResponse`() = runBlocking {
        val fx = Fixture()
        fx.authApi.requestOtpAction = {
            ApiResponse(success = false, code = "SOMETHING", message = "not actually ok", data = null)
        }

        val result = fx.repository.requestPasswordResetOtp("alice@example.com")

        assertTrue(result is AccountRecoveryResult.InvalidResponse)
        assertEquals(SessionState.Unknown, fx.sessionManager.sessionState.value)
    }

    @Test
    fun `account recovery never authenticates even when an existing session is already established`() = runBlocking {
        val fx = Fixture()
        fx.authApi.loginAction = { successResponse() }
        fx.repository.login(LoginRequest("alice", "password123"))
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)

        fx.authApi.resetPasswordAction = {
            ApiResponse(success = true, message = "Password reset", data = "Password reset")
        }
        fx.repository.resetPassword("alice@example.com", "123456", "new-password-123")

        // The pre-existing authenticated session must remain exactly as it was --
        // a password-recovery call must never clear or otherwise alter it.
        assertEquals(SessionState.Authenticated, fx.sessionManager.sessionState.value)
        assertEquals("access-123", fx.tokenStorage.getAccessToken())
    }
}
