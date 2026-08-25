package com.connectx.app.core.session

import com.connectx.app.data.local.auth.TokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for local session state, exposed as
 * `StateFlow<SessionState>`. N3.1 scope only: read/write [TokenStorage] and
 * publish [SessionState] -- no network calls, no JWT parsing/validation, no
 * expiration checking. See N3.0 (docs Section 35) for why: the backend gives
 * Android no cheap way to validate a token locally, so restoration is
 * deliberately optimistic -- "tokens exist" is treated as "was authenticated,"
 * and the first real API call's 401 (N3.3/N3.7) is what actually discovers an
 * expired/invalid token.
 *
 * `MutableStateFlow` publishes updates atomically and is safe to read/write
 * from multiple threads/coroutines without additional locking here.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStorage: TokenStorage
) {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Unknown)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * Reads persisted tokens and publishes the resulting [SessionState]. Call
     * once at app startup (N3.6 will wire the actual call site).
     *
     * A COMPLETE pair -> [SessionState.Authenticated]. No tokens at all, or
     * only one of the two present, -> [SessionState.Unauthenticated]. A
     * partial pair is never "repaired" -- it's cleared outright and treated
     * as no session, per N3.0/N3.1's explicit decision not to guess which of
     * the two stored values (if either) is still trustworthy.
     */
    fun restoreSession() {
        if (tokenStorage.getTokens() != null) {
            _sessionState.value = SessionState.Authenticated
            return
        }

        val hasPartialTokens = tokenStorage.getAccessToken() != null ||
            tokenStorage.getRefreshToken() != null
        if (hasPartialTokens) {
            tokenStorage.clearTokens()
        }
        _sessionState.value = SessionState.Unauthenticated
    }

    /**
     * Persists a freshly obtained token pair (e.g. from a future N3.2 login/
     * register/refresh call) and marks the session authenticated.
     */
    fun setAuthenticated(accessToken: String, refreshToken: String) {
        tokenStorage.saveTokens(accessToken, refreshToken)
        _sessionState.value = SessionState.Authenticated
    }

    /**
     * Clears persisted tokens and marks the session unauthenticated. Local
     * storage/state only -- the backend's `/auth/logout` has no server-side
     * revocation to call (N3.0 Section 7), so no network request belongs here;
     * calling it (if ever desired) is an `AuthRepository` concern for N3.2+.
     */
    fun clearSession() {
        tokenStorage.clearTokens()
        _sessionState.value = SessionState.Unauthenticated
    }
}
