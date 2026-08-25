package com.connectx.app.core.network.auth

import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import com.connectx.app.data.remote.auth.AccountRecoveryResult
import com.connectx.app.data.remote.auth.AuthRepository
import com.connectx.app.data.remote.auth.AuthResult
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import com.connectx.app.data.remote.auth.model.UserDto
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies [TokenAuthenticator]'s 401 -> refresh -> retry behavior, single-flight
 * concurrency, retry-limit, refresh-recursion safety, and the credential-rejection
 * vs. network-failure distinction. A hand-written [FakeAuthRepository] (no
 * mocking framework) stands in for the real `AuthRepositoryImpl` -- this class
 * intentionally tests `TokenAuthenticator` in isolation from the real network
 * call `AuthRepository.refresh` would make (that boundary is already covered
 * by `AuthRepositoryImplTest`). A real [MockWebServer] is used only where an
 * actual OkHttp round trip is the simplest way to prove the behavior (401
 * triggering refresh, the retried request's headers, concurrency, and that a
 * 403/500 never reaches the authenticator at all -- the last two rely on
 * OkHttp's own `Authenticator` contract, not custom logic in this class).
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private var client: OkHttpClient? = null

    private class FakeTokenStorage(
        accessToken: String?,
        refreshToken: String?
    ) : TokenStorage {
        private var access: String? = accessToken
        private var refresh: String? = refreshToken

        override fun saveTokens(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override fun getAccessToken(): String? = access
        override fun getRefreshToken(): String? = refresh
        override fun getTokens(): TokenPair? {
            val a = access ?: return null
            val r = refresh ?: return null
            return TokenPair(a, r)
        }

        override fun clearTokens() {
            access = null
            refresh = null
        }

        override fun hasTokens(): Boolean = getTokens() != null
    }

    /** [refresh] does the real work; [logout] mirrors SessionManager.clearSession by clearing storage. */
    private class FakeAuthRepository(private val tokenStorage: TokenStorage) : AuthRepository {
        var refreshAction: (suspend (String) -> AuthResult)? = null
        var refreshCallCount = 0
        var logoutCallCount = 0

        override suspend fun register(request: RegisterRequest): AuthResult = error("not used in this test")
        override suspend fun login(request: LoginRequest): AuthResult = error("not used in this test")

        override suspend fun refresh(refreshToken: String): AuthResult {
            refreshCallCount++
            return refreshAction?.invoke(refreshToken) ?: error("refreshAction not configured for this test")
        }

        override fun logout() {
            logoutCallCount++
            tokenStorage.clearTokens()
        }

        override suspend fun requestPasswordResetOtp(email: String): AccountRecoveryResult =
            error("not used in this test")

        override suspend fun verifyPasswordResetOtp(email: String, otpCode: String): AccountRecoveryResult =
            error("not used in this test")

        override suspend fun resetPassword(email: String, otpCode: String, newPassword: String): AccountRecoveryResult =
            error("not used in this test")
    }

    private val dummyUser = UserDto(id = 1L, username = "alice", email = "alice@example.com")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun unauthorizedResponse(accessTokenUsed: String?): Response {
        val request = Request.Builder()
            .url("https://example.invalid/protected")
            .apply { accessTokenUsed?.let { header("Authorization", "Bearer $it") } }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    // ---- End-to-end (real OkHttp + MockWebServer) ----

    @Test
    fun `a 401 triggers exactly one refresh and the retried request uses the new access token`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access-token", refreshToken = "old-refresh-token")
        val authRepository = FakeAuthRepository(tokenStorage)
        authRepository.refreshAction = { refreshTokenArg ->
            assertEquals("old-refresh-token", refreshTokenArg)
            tokenStorage.saveTokens("new-access-token", "new-refresh-token")
            AuthResult.Success(dummyUser)
        }
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)
        val interceptor = AuthInterceptor(tokenStorage)

        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .authenticator(authenticator)
            .build()

        client!!.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val firstRequest = server.takeRequest()
        assertEquals("Bearer old-access-token", firstRequest.headers["Authorization"])
        val secondRequest = server.takeRequest()
        assertEquals("Bearer new-access-token", secondRequest.headers["Authorization"])

        assertEquals(1, authRepository.refreshCallCount)
        assertEquals("new-access-token", tokenStorage.getAccessToken())
        assertEquals("new-refresh-token", tokenStorage.getRefreshToken())
    }

    @Test
    fun `a 403 response never invokes the authenticator`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access-token", refreshToken = "old-refresh-token")
        val authRepository = FakeAuthRepository(tokenStorage)
        client = OkHttpClient.Builder().authenticator(TokenAuthenticator(tokenStorage, authRepository)).build()
        server.enqueue(MockResponse.Builder().code(403).build())

        client!!.newCall(Request.Builder().url(server.url("/forbidden")).build()).execute().use {
            assertEquals(403, it.code)
        }

        assertEquals(0, authRepository.refreshCallCount)
    }

    @Test
    fun `a 500 response never invokes the authenticator`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access-token", refreshToken = "old-refresh-token")
        val authRepository = FakeAuthRepository(tokenStorage)
        client = OkHttpClient.Builder().authenticator(TokenAuthenticator(tokenStorage, authRepository)).build()
        server.enqueue(MockResponse.Builder().code(500).build())

        client!!.newCall(Request.Builder().url(server.url("/boom")).build()).execute().use {
            assertEquals(500, it.code)
        }

        assertEquals(0, authRepository.refreshCallCount)
    }

    @Test
    fun `concurrent 401s across multiple threads produce exactly one refresh call`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access-token", refreshToken = "old-refresh-token")
        val authRepository = FakeAuthRepository(tokenStorage)
        authRepository.refreshAction = {
            // A small delay so concurrent callers genuinely pile up on the mutex
            // rather than happening to run sequentially by accident.
            Thread.sleep(75)
            tokenStorage.saveTokens("new-access-token", "new-refresh-token")
            AuthResult.Success(dummyUser)
        }
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.headers["Authorization"] == "Bearer new-access-token") {
                    MockResponse.Builder().code(200).build()
                } else {
                    MockResponse.Builder().code(401).build()
                }
            }
        }

        client = OkHttpClient.Builder().authenticator(authenticator).build()

        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val statusCodes = ConcurrentLinkedQueue<Int>()
        repeat(threadCount) {
            Thread {
                try {
                    val request = Request.Builder()
                        .url(server.url("/protected"))
                        .header("Authorization", "Bearer old-access-token")
                        .build()
                    client!!.newCall(request).execute().use { statusCodes.add(it.code) }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(threadCount, statusCodes.size)
        assertTrue(statusCodes.all { it == 200 })
        assertEquals(1, authRepository.refreshCallCount)
    }

    // ---- Direct authenticate() calls (no HTTP round trip needed) ----

    @Test
    fun `a request that has already been retried once is not retried again`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access", refreshToken = "old-refresh")
        val authRepository = FakeAuthRepository(tokenStorage)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        val originalRequest = Request.Builder().url("https://example.invalid/protected").build()
        val firstResponse = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
        val secondRequest = originalRequest.newBuilder().header("Authorization", "Bearer some-token").build()
        val secondResponse = Response.Builder()
            .request(secondRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(firstResponse)
            .build()

        val result = authenticator.authenticate(null, secondResponse)

        assertNull(result)
        assertEquals(0, authRepository.refreshCallCount)
    }

    @Test
    fun `refresh rejected by the backend clears the session and does not retry`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access", refreshToken = "old-refresh")
        val authRepository = FakeAuthRepository(tokenStorage)
        authRepository.refreshAction = { AuthResult.ApiError(401, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired") }
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        val result = authenticator.authenticate(null, unauthorizedResponse("old-access"))

        assertNull(result)
        assertEquals(1, authRepository.logoutCallCount)
        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
    }

    @Test
    fun `refresh failing due to a network error does not clear the session`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access", refreshToken = "old-refresh")
        val authRepository = FakeAuthRepository(tokenStorage)
        authRepository.refreshAction = { AuthResult.NetworkError("Unable to resolve host") }
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        val result = authenticator.authenticate(null, unauthorizedResponse("old-access"))

        assertNull(result)
        assertEquals(0, authRepository.logoutCallCount)
        assertEquals("old-access", tokenStorage.getAccessToken())
        assertEquals("old-refresh", tokenStorage.getRefreshToken())
    }

    @Test
    fun `no stored refresh token clears the session without attempting a refresh call`() {
        val tokenStorage = FakeTokenStorage(accessToken = "old-access", refreshToken = null)
        val authRepository = FakeAuthRepository(tokenStorage)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        val result = authenticator.authenticate(null, unauthorizedResponse("old-access"))

        assertNull(result)
        assertEquals(0, authRepository.refreshCallCount)
        assertEquals(1, authRepository.logoutCallCount)
    }

    @Test
    fun `a token already refreshed by another caller is reused without refreshing again`() {
        val tokenStorage = FakeTokenStorage(accessToken = "already-new-access", refreshToken = "already-new-refresh")
        val authRepository = FakeAuthRepository(tokenStorage)
        val authenticator = TokenAuthenticator(tokenStorage, authRepository)

        val result = authenticator.authenticate(null, unauthorizedResponse("stale-access"))

        assertEquals("Bearer already-new-access", result?.header("Authorization"))
        assertEquals(0, authRepository.refreshCallCount)
    }
}
