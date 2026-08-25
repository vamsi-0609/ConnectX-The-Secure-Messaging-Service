package com.connectx.app.data.remote.auth

import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest

/**
 * The boundary between the network authentication contract ([AuthApi]) and
 * application authentication state ([com.connectx.app.core.session.SessionManager]).
 * Future ViewModels/UI depend on this interface only -- never on [AuthApi],
 * [com.connectx.app.data.local.auth.TokenStorage], or
 * [com.connectx.app.core.session.SessionManager] directly.
 *
 * N3.2 added register/login plus a local-only [logout]. N3.3 adds [refresh],
 * consumed exclusively by `core.network.auth.TokenAuthenticator` -- not
 * intended as a general-purpose call site.
 */
interface AuthRepository {

    /**
     * Registers a new account. The backend's `/auth/register` returns a real,
     * immediately-usable access/refresh token pair (confirmed directly from
     * `AuthController`/`AuthService` source for this phase, not assumed) --
     * a successful registration therefore establishes the authenticated
     * session exactly like [login], not merely creates an account.
     */
    suspend fun register(request: RegisterRequest): AuthResult

    suspend fun login(request: LoginRequest): AuthResult

    /**
     * Exchanges a refresh token for a brand-new access+refresh pair (the
     * backend always rotates both, per N3.0 Section 35). Deliberately takes
     * the refresh token as a parameter rather than reading it from storage
     * itself -- this repository never touches `TokenStorage` directly (same
     * boundary [logout] and the rest of this interface already keep); the
     * caller (`TokenAuthenticator`, which already needs `TokenStorage` for
     * other reasons) supplies it, exactly like [login]/[register] receive
     * their own request DTOs from the caller rather than sourcing them
     * internally. Shares [register]/[login]'s exact validate-then-persist
     * behavior -- a response missing either token is rejected the same way.
     */
    suspend fun refresh(refreshToken: String): AuthResult

    /**
     * Clears the local session only -- [TokenStorage.clearTokens] +
     * [SessionManager.clearSession]. No network request: the backend's
     * `/auth/logout` has no server-side revocation effect to trigger (N3.0
     * Section 7), so calling it would accomplish nothing here. A future phase
     * may add a best-effort server-side call on top of this without changing
     * this method's meaning. Also used by `TokenAuthenticator` to clear the
     * session when a refresh attempt is rejected (an invalid/expired refresh
     * token, as opposed to a mere network failure -- see docs Section 38).
     */
    fun logout()

    /**
     * Requests a one-time password be emailed for account recovery. Session
     * state is never touched by this call -- requesting an OTP does not
     * authenticate anyone (see [AccountRecoveryResult]).
     */
    suspend fun requestPasswordResetOtp(email: String): AccountRecoveryResult

    /**
     * Verifies a previously requested OTP without consuming it for a reset.
     * Session state is never touched -- the backend does not return
     * authentication credentials from this endpoint.
     */
    suspend fun verifyPasswordResetOtp(email: String, otpCode: String): AccountRecoveryResult

    /**
     * Resets the account's password using a verified OTP. Session state is
     * never touched -- the backend's `/forgot-password/reset-password`
     * returns only a confirmation message, never a token pair; the user
     * still has to log in afterward with the new password, exactly like the
     * backend's own confirmation text says ("You can now login with your new
     * password").
     */
    suspend fun resetPassword(email: String, otpCode: String, newPassword: String): AccountRecoveryResult
}
