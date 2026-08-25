package com.connectx.app.data.local.auth

/**
 * Secure persistence boundary for the access/refresh token pair. The only
 * concrete implementation ([EncryptedTokenStorage]) backs this with Android
 * Keystore-encrypted storage -- callers never see or depend on that detail.
 *
 * [hasTokens] and [getTokens] only ever report a COMPLETE pair as present.
 * N3.0 deliberately does not attempt to "repair" a partial state (only one of
 * the two values persisted, e.g. from an interrupted write) -- callers that
 * detect a partial state are expected to call [clearTokens] and treat the
 * session as unauthenticated (see `SessionManager.restoreSession`).
 */
interface TokenStorage {

    /** Persists both values, replacing whatever was previously stored. */
    fun saveTokens(accessToken: String, refreshToken: String)

    fun getAccessToken(): String?

    fun getRefreshToken(): String?

    /** Non-null only when BOTH tokens are present; null on no-tokens or partial state. */
    fun getTokens(): TokenPair?

    fun clearTokens()

    /** True only when BOTH tokens are present -- see [getTokens]. */
    fun hasTokens(): Boolean
}
