package com.connectx.app.core.session

import com.connectx.app.core.network.auth.AuthInterceptor
import com.connectx.app.core.network.auth.TokenAuthenticator
import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import com.connectx.app.data.remote.auth.AuthApi
import com.connectx.app.data.remote.auth.AuthRepositoryImpl
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.ForgotPasswordRequestDto
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RefreshTokenRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import com.connectx.app.data.remote.auth.model.ResetPasswordRequestDto
import com.connectx.app.data.remote.auth.model.UserDto
import com.connectx.app.data.remote.auth.model.VerifyOtpRequestDto
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * N3.4: verifies the REAL [SessionManager] as observed through the fully
 * wired stack (`AuthRepositoryImpl` + `TokenAuthenticator` + `AuthInterceptor`,
 * hitting a real [MockWebServer]) -- not a fake standing in for
 * `AuthRepository`/`SessionManager` the way N3.3's `TokenAuthenticatorTest`
 * used. That file proved `TokenAuthenticator`'s own logic in isolation;
 * this file proves the layers actually integrate: a refresh triggered deep
 * in the OkHttp layer is visible, correctly, on the exact same
 * `SessionManager.sessionState` a UI layer would observe. Also covers
 * process-death/recreation restoration (a new `SessionManager` instance
 * reading tokens a DIFFERENT, now-discarded instance persisted), which no
 * N3.1-N3.3 test exercised (their restore tests always pre-populated
 * storage directly, never via a first `SessionManager`).
 *
 * No mocking framework; no real backend; only a local [MockWebServer] and
 * hand-written fakes.
 */
class SessionIntegrationTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dummyUser = UserDto(id = 1L, username = "alice", email = "alice@example.com")

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

    private class FakeAuthApi : AuthApi {
        var refreshAction: (suspend () -> ApiResponse<AuthResponse>)? = null

        override suspend fun register(request: RegisterRequest): ApiResponse<AuthResponse> =
            error("not used in this test")

        override suspend fun login(request: LoginRequest): ApiResponse<AuthResponse> =
            error("not used in this test")

        override suspend fun refresh(request: RefreshTokenRequest): ApiResponse<AuthResponse> =
            refreshAction?.invoke() ?: error("refreshAction not configured for this test")

        override suspend fun requestForgotPasswordOtp(request: ForgotPasswordRequestDto): ApiResponse<String> =
            error("not used in this test")

        override suspend fun verifyForgotPasswordOtp(request: VerifyOtpRequestDto): ApiResponse<String> =
            error("not used in this test")

        override suspend fun resetPassword(request: ResetPasswordRequestDto): ApiResponse<String> =
            error("not used in this test")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a real refresh success keeps SessionManager Authenticated with the rotated tokens`() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.saveTokens("old-access-token", "old-refresh-token")
        val sessionManager = SessionManager(tokenStorage)
        sessionManager.restoreSession()
        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)

        val authApi = FakeAuthApi()
        authApi.refreshAction = {
            ApiResponse(
                success = true,
                data = AuthResponse(accessToken = "new-access-token", refreshToken = "new-refresh-token", user = dummyUser)
            )
        }
        val authRepository = AuthRepositoryImpl(authApi, sessionManager, json)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage))
            .authenticator(authenticator)
            .build()

        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
        assertEquals("new-access-token", tokenStorage.getAccessToken())
        assertEquals("new-refresh-token", tokenStorage.getRefreshToken())
    }

    @Test
    fun `a real refresh rejection transitions SessionManager to Unauthenticated and clears both tokens`() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.saveTokens("old-access-token", "old-refresh-token")
        val sessionManager = SessionManager(tokenStorage)
        sessionManager.restoreSession()
        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)

        val authApi = FakeAuthApi()
        authApi.refreshAction = {
            ApiResponse(success = false, code = "INVALID_REFRESH_TOKEN", message = "Refresh token is invalid or expired", data = null)
        }
        val authRepository = AuthRepositoryImpl(authApi, sessionManager, json)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage))
            .authenticator(authenticator)
            .build()

        server.enqueue(MockResponse.Builder().code(401).build())

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(401, it.code)
        }

        assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)
        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
    }

    @Test
    fun `a network failure during refresh leaves SessionManager Authenticated with credentials intact`() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.saveTokens("old-access-token", "old-refresh-token")
        val sessionManager = SessionManager(tokenStorage)
        sessionManager.restoreSession()

        val authApi = FakeAuthApi()
        authApi.refreshAction = { throw java.io.IOException("Unable to resolve host") }
        val authRepository = AuthRepositoryImpl(authApi, sessionManager, json)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage))
            .authenticator(authenticator)
            .build()

        server.enqueue(MockResponse.Builder().code(401).build())

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(401, it.code)
        }

        assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
        assertEquals("old-access-token", tokenStorage.getAccessToken())
        assertEquals("old-refresh-token", tokenStorage.getRefreshToken())
    }

    @Test
    fun `a new SessionManager instance restores a session persisted by a prior, now-discarded instance`() {
        // Simulates process death: sessionManagerBeforeDeath authenticates and is
        // then discarded (as every in-memory Kotlin object would be on process
        // death); only the backing TokenStorage survives, exactly like real
        // EncryptedSharedPreferences persisting to disk.
        val sharedStorage = FakeTokenStorage()
        val sessionManagerBeforeDeath = SessionManager(sharedStorage)
        sessionManagerBeforeDeath.setAuthenticated("access-before-death", "refresh-before-death")
        assertEquals(SessionState.Authenticated, sessionManagerBeforeDeath.sessionState.value)

        val sessionManagerAfterRestart = SessionManager(sharedStorage)
        assertEquals(SessionState.Unknown, sessionManagerAfterRestart.sessionState.value)

        sessionManagerAfterRestart.restoreSession()

        assertEquals(SessionState.Authenticated, sessionManagerAfterRestart.sessionState.value)
        assertEquals("access-before-death", sharedStorage.getAccessToken())
        assertEquals("refresh-before-death", sharedStorage.getRefreshToken())
    }

    /**
     * N3.7: every other test in this suite (and in `TokenAuthenticatorTest`)
     * uses a hand-written [FakeAuthApi] whose `refreshAction` returns a
     * ready-made Kotlin object -- real JSON parsing never actually runs, so
     * the `catch (e: SerializationException)` path inside
     * `AuthRepositoryImpl.authenticate` has never been exercised by any
     * existing test. This test closes that gap: a REAL `AuthApi` built via
     * Retrofit (matching `NetworkModule`'s actual unauthenticated client
     * exactly -- no interceptor) receives genuinely malformed bytes from a
     * second, independent `MockWebServer` standing in for the refresh
     * endpoint. Proves the existing design's already-correct behavior
     * (`UnexpectedError` is grouped with credential-rejection in
     * `TokenAuthenticator`, per its own doc comment) results in a CLEAN,
     * fully-consistent `Unauthenticated` state -- both tokens atomically
     * `null`, never one cleared and the other stale -- not a corrupted
     * half-written credential state.
     */
    @Test
    fun `a malformed refresh response is treated as a clean session invalidation, not token corruption`() {
        val refreshServer = MockWebServer()
        refreshServer.start()
        try {
            val tokenStorage = FakeTokenStorage()
            tokenStorage.saveTokens("old-access-token", "old-refresh-token")
            val sessionManager = SessionManager(tokenStorage)
            sessionManager.restoreSession()
            assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)

            val unauthenticatedClient = OkHttpClient.Builder().build()
            val retrofit = Retrofit.Builder()
                .baseUrl(refreshServer.url("/"))
                .client(unauthenticatedClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            val realAuthApi = retrofit.create(AuthApi::class.java)
            val authRepository = AuthRepositoryImpl(realAuthApi, sessionManager, json)
            val authenticator = TokenAuthenticator(tokenStorage, authRepository)
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenStorage))
                .authenticator(authenticator)
                .build()

            server.enqueue(MockResponse.Builder().code(401).build())
            refreshServer.enqueue(
                MockResponse.Builder().code(200).body("{ this is not valid json at all").build()
            )

            client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
                // The malformed refresh means there is no new token to retry with --
                // the original 401 propagates back to the caller, not a crash.
                assertEquals(401, it.code)
            }

            assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)
            assertNull(tokenStorage.getAccessToken())
            assertNull(tokenStorage.getRefreshToken())
        } finally {
            refreshServer.close()
        }
    }
}
