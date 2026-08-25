package com.connectx.app.data.remote.auth

import com.connectx.app.data.remote.auth.model.UserDto

/**
 * The result of an [AuthRepository] authentication operation (register/login) --
 * the boundary future ViewModels/UI consume instead of Retrofit/OkHttp
 * exceptions or raw [com.connectx.app.core.network.model.ApiResponse] shapes.
 *
 * Deliberately flat, not a deep hierarchy: exactly the four failure categories
 * N3.2 needs to distinguish (backend/API error, network failure, an
 * authentication response the backend called successful but that doesn't
 * actually carry what's needed, and anything else unexpected -- including a
 * local persistence failure, see [AuthRepositoryImpl]) plus [Success]. No
 * per-field-validation-error type, no retry metadata, no request-id tracking --
 * this is only authentication, not a general-purpose network error framework.
 */
sealed interface AuthResult {

    /** Tokens were validated, persisted, and the session is now [com.connectx.app.core.session.SessionState.Authenticated]. */
    data class Success(val user: UserDto) : AuthResult

    /** The backend responded with a non-2xx status. Mirrors `ErrorResponse` where available. */
    data class ApiError(val status: Int?, val code: String?, val message: String?) : AuthResult

    /** No response was received at all -- no connectivity, timeout, connection refused, etc. */
    data class NetworkError(val message: String?) : AuthResult

    /**
     * The backend call succeeded (2xx, valid JSON) but the resulting
     * [com.connectx.app.core.network.model.ApiResponse] doesn't actually carry
     * a usable authentication result -- `success == false`, `data == null`, or
     * an access/refresh token that's missing/blank. Never persisted, never
     * authenticates the session.
     */
    data class InvalidResponse(val message: String) : AuthResult

    /** Anything else -- a malformed/unparseable response body, or a local token-persistence failure. */
    data class UnexpectedError(val message: String?) : AuthResult
}
