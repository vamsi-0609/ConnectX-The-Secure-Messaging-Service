package com.connectx.app.core.session

import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only verification of SessionManager's state-transition logic, deliberately
 * decoupled from Android/EncryptedSharedPreferences via a hand-written in-memory
 * [TokenStorage] fake (no mocking framework, no instrumented test environment
 * needed) -- see docs Section 36 for why the actual encrypted-storage
 * implementation is verified separately (build + Hilt graph + emulator install,
 * not a JVM unit test).
 */
class SessionManagerTest {

    private class FakeTokenStorage : TokenStorage {
        private var accessToken: String? = null
        private var refreshToken: String? = null

        override fun saveTokens(accessToken: String, refreshToken: String) {
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

    @Test
    fun `initial state is Unknown before restoreSession is called`() {
        val sessionManager = SessionManager(FakeTokenStorage())
        assertEquals(SessionState.Unknown, sessionManager.sessionState.value)
    }

    @Test
    fun `restoreSession with no tokens transitions to Unauthenticated`() {
        val sessionManager = SessionManager(FakeTokenStorage())

        sessionManager.restoreSession()

        assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)
    }

    @Test
    fun `restoreSession with a complete token pair transitions to Authenticated`() {
        val storage = FakeTokenStorage()
        storage.saveTokens("access-123", "refresh-456")
        val sessionManager = SessionManager(storage)

        sessionManager.restoreSession()

        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
    }

    @Test
    fun `restoreSession with only an access token clears it and transitions to Unauthenticated`() {
        val partial = PartialAccessOnlyTokenStorage(accessToken = "access-only")
        val manager = SessionManager(partial)

        manager.restoreSession()

        assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
        assertNull(partial.getAccessToken())
        assertNull(partial.getRefreshToken())
    }

    @Test
    fun `restoreSession with only a refresh token clears it and transitions to Unauthenticated`() {
        val trulyPartial = PartialRefreshOnlyTokenStorage(refreshToken = "refresh-only")
        val manager = SessionManager(trulyPartial)

        manager.restoreSession()

        assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
        assertNull(trulyPartial.getAccessToken())
        assertNull(trulyPartial.getRefreshToken())
    }

    @Test
    fun `clearSession clears storage and transitions to Unauthenticated`() {
        val storage = FakeTokenStorage()
        storage.saveTokens("access-123", "refresh-456")
        val sessionManager = SessionManager(storage)
        sessionManager.restoreSession()
        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)

        sessionManager.clearSession()

        assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
    }

    @Test
    fun `setAuthenticated saves tokens and transitions Unauthenticated to Authenticated`() {
        val storage = FakeTokenStorage()
        val sessionManager = SessionManager(storage)
        sessionManager.restoreSession()
        assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)

        sessionManager.setAuthenticated("new-access", "new-refresh")

        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
        assertEquals("new-access", storage.getAccessToken())
        assertEquals("new-refresh", storage.getRefreshToken())
    }

    /** A [TokenStorage] fake that only ever has an access token, never a refresh token. */
    private class PartialAccessOnlyTokenStorage(accessToken: String?) : TokenStorage {
        private var access: String? = accessToken

        override fun saveTokens(accessToken: String, refreshToken: String) {
            access = accessToken
        }

        override fun getAccessToken(): String? = access
        override fun getRefreshToken(): String? = null
        override fun getTokens(): TokenPair? = null
        override fun clearTokens() {
            access = null
        }
        override fun hasTokens(): Boolean = false
    }

    /** A [TokenStorage] fake that only ever has a refresh token, never an access token. */
    private class PartialRefreshOnlyTokenStorage(refreshToken: String?) : TokenStorage {
        private var refresh: String? = refreshToken

        override fun saveTokens(accessToken: String, refreshToken: String) {
            refresh = refreshToken
        }

        override fun getAccessToken(): String? = null
        override fun getRefreshToken(): String? = refresh
        override fun getTokens(): TokenPair? = null
        override fun clearTokens() {
            refresh = null
        }
        override fun hasTokens(): Boolean = false
    }
}
