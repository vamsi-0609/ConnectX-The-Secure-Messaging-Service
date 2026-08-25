package com.connectx.app.data.remote.auth

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.core.network.model.ErrorResponse
import com.connectx.app.core.session.SessionManager
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.ForgotPasswordRequestDto
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RefreshTokenRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import com.connectx.app.data.remote.auth.model.ResetPasswordRequestDto
import com.connectx.app.data.remote.auth.model.VerifyOtpRequestDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AuthRepository] implementation wiring [AuthApi] to
 * [com.connectx.app.data.local.auth.TokenStorage] (indirectly, via
 * [SessionManager] -- this class never touches `TokenStorage` directly, per
 * N3.2's boundary) and [SessionManager]. Uses the same [Json] instance
 * `NetworkModule` already provides for Retrofit's converter -- no second JSON
 * configuration exists.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val json: Json
) : AuthRepository {

    override suspend fun register(request: RegisterRequest): AuthResult =
        authenticate { authApi.register(request) }

    override suspend fun login(request: LoginRequest): AuthResult =
        authenticate { authApi.login(request) }

    override suspend fun refresh(refreshToken: String): AuthResult =
        authenticate { authApi.refresh(RefreshTokenRequest(refreshToken)) }

    override fun logout() {
        sessionManager.clearSession()
    }

    /**
     * None of the three recovery operations authenticate the caller (see
     * [AccountRecoveryResult]'s doc comment) -- [recover] never touches
     * [SessionManager] at all, in contrast to [authenticate] below, which
     * always does on success.
     */
    override suspend fun requestPasswordResetOtp(email: String): AccountRecoveryResult =
        recover { authApi.requestForgotPasswordOtp(ForgotPasswordRequestDto(email)) }

    override suspend fun verifyPasswordResetOtp(email: String, otpCode: String): AccountRecoveryResult =
        recover { authApi.verifyForgotPasswordOtp(VerifyOtpRequestDto(email, otpCode)) }

    override suspend fun resetPassword(email: String, otpCode: String, newPassword: String): AccountRecoveryResult =
        recover { authApi.resetPassword(ResetPasswordRequestDto(email, otpCode, newPassword)) }

    /**
     * Shared register/login/refresh flow: call -> validate the envelope ->
     * validate the tokens -> persist + update session (only after both
     * validations pass) -> [AuthResult.Success]. Every failure path returns
     * before any token is persisted or [SessionManager] is touched.
     */
    private suspend fun authenticate(call: suspend () -> ApiResponse<AuthResponse>): AuthResult {
        val response = try {
            call()
        } catch (e: HttpException) {
            val parsed = e.parseApiError()
            return AuthResult.ApiError(parsed.status, parsed.code, parsed.message)
        } catch (e: IOException) {
            // No response at all: no connectivity, timeout, connection refused, etc.
            return AuthResult.NetworkError(e.message)
        } catch (e: SerializationException) {
            // A malformed/unexpected response body -- a real bug, not a network condition.
            return AuthResult.UnexpectedError(e.message ?: "Failed to parse the authentication response")
        }

        if (!response.success) {
            return AuthResult.InvalidResponse(response.message ?: "Authentication request was not successful")
        }

        val authResponse = response.data
            ?: return AuthResult.InvalidResponse("Authentication response did not include any data")

        val accessToken = authResponse.accessToken
        val refreshToken = authResponse.refreshToken
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            return AuthResult.InvalidResponse("Authentication response is missing an access or refresh token")
        }

        return try {
            sessionManager.setAuthenticated(accessToken, refreshToken)
            AuthResult.Success(authResponse.user)
        } catch (e: Exception) {
            // setAuthenticated saves tokens BEFORE flipping session state (see
            // SessionManager) -- if persistence throws, that assignment is never
            // reached, so the session is never incorrectly marked Authenticated.
            AuthResult.UnexpectedError(e.message ?: "Failed to persist the authentication session")
        }
    }

    /**
     * Shared account-recovery flow: call -> map the envelope directly to
     * [AccountRecoveryResult] -- no token validation, no [SessionManager]
     * interaction, ever (see [AccountRecoveryResult]'s doc comment for why).
     */
    private suspend fun recover(call: suspend () -> ApiResponse<String>): AccountRecoveryResult {
        val response = try {
            call()
        } catch (e: HttpException) {
            val parsed = e.parseApiError()
            return AccountRecoveryResult.ApiError(parsed.status, parsed.code, parsed.message)
        } catch (e: IOException) {
            return AccountRecoveryResult.NetworkError(e.message)
        } catch (e: SerializationException) {
            return AccountRecoveryResult.UnexpectedError(e.message ?: "Failed to parse the response")
        }

        if (!response.success) {
            return AccountRecoveryResult.InvalidResponse(response.message ?: "Request was not successful")
        }

        return AccountRecoveryResult.Success(response.data)
    }

    private data class ParsedApiError(val status: Int?, val code: String?, val message: String?)

    /** Shared by [authenticate] and [recover] -- parses the backend's `ErrorResponse` body where possible. */
    private fun HttpException.parseApiError(): ParsedApiError {
        val httpStatus = code()
        val rawErrorBody = response()?.errorBody()?.string()
        val parsed = rawErrorBody?.let {
            try {
                json.decodeFromString<ErrorResponse>(it)
            } catch (e: SerializationException) {
                null
            }
        }
        return ParsedApiError(
            status = parsed?.status ?: httpStatus,
            code = parsed?.code,
            message = parsed?.message ?: message()
        )
    }
}
