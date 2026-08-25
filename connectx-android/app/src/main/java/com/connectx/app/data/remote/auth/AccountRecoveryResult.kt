package com.connectx.app.data.remote.auth

/**
 * The result of an [AuthRepository] account-recovery operation (request OTP,
 * verify OTP, reset password) -- deliberately a separate type from
 * [AuthResult], not a reuse of it. All three recovery endpoints return
 * `ApiResponse<String>` on the backend (a plain confirmation message, e.g.
 * "OTP sent"), never an `AuthResponse`/token pair -- forcing [AuthResult]'s
 * `Success(user: UserDto)` shape onto them would be wrong, since none of
 * these operations authenticate the caller or return a user. The four
 * failure categories are intentionally identical in spirit and field shape
 * to [AuthResult]'s (backend/API error, network failure, a well-formed but
 * unusable response, anything else unexpected) -- that consistency is
 * "following the established architecture where applicable"; only the
 * `Success` payload genuinely differs.
 */
sealed interface AccountRecoveryResult {

    /** The backend accepted the request; [message] is its plain confirmation text (e.g. "OTP sent"). */
    data class Success(val message: String?) : AccountRecoveryResult

    /** The backend responded with a non-2xx status. Mirrors `ErrorResponse` where available. */
    data class ApiError(val status: Int?, val code: String?, val message: String?) : AccountRecoveryResult

    /** No response was received at all -- no connectivity, timeout, connection refused, etc. */
    data class NetworkError(val message: String?) : AccountRecoveryResult

    /** The backend call succeeded (2xx, valid JSON) but `success == false`. */
    data class InvalidResponse(val message: String) : AccountRecoveryResult

    /** Anything else -- a malformed/unparseable response body. */
    data class UnexpectedError(val message: String?) : AccountRecoveryResult
}
