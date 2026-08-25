package com.connectx.app.core.network.auth

import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import com.connectx.app.data.remote.auth.AuthApi
import com.connectx.app.data.remote.auth.model.RefreshTokenRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * N3.5: behavioral security verification specific to token exposure, not
 * already covered by N3.1-N3.4's tests. Two properties, each proven against
 * a real [MockWebServer] (never a fake, since a fake could hide a real
 * header/body-construction bug):
 *
 * 1. [AuthInterceptor] attaches ONLY the access token, even when a refresh
 *    token is simultaneously present in storage -- the refresh token value
 *    must never appear anywhere the interceptor touches.
 * 2. A real refresh call (built exactly the way `NetworkModule` builds the
 *    unauthenticated client -- plain `OkHttpClient`, no interceptor) sends
 *    the refresh token ONLY inside the JSON request body, and the request
 *    carries no `Authorization` header at all.
 */
class AuthSecurityTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class FakeTokenStorage(
        private val accessToken: String?,
        private val refreshToken: String?
    ) : TokenStorage {
        override fun saveTokens(accessToken: String, refreshToken: String) {}
        override fun getAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = refreshToken
        override fun getTokens(): TokenPair? {
            val a = accessToken ?: return null
            val r = refreshToken ?: return null
            return TokenPair(a, r)
        }
        override fun clearTokens() {}
        override fun hasTokens(): Boolean = getTokens() != null
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
    fun `AuthInterceptor attaches only the access token even when a refresh token is also stored`() {
        val tokenStorage = FakeTokenStorage(
            accessToken = "access-token-value",
            refreshToken = "refresh-token-value-must-never-leak"
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage))
            .build()
        server.enqueue(MockResponse.Builder().code(200).build())

        client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer access-token-value", recorded.headers["Authorization"])
        assertFalse(
            "the refresh token must never appear in any request header",
            recorded.headers.toString().contains("refresh-token-value-must-never-leak")
        )
    }

    @Test
    fun `a live refresh call sends the refresh token only in the JSON body and carries no Authorization header`() {
        // Mirrors NetworkModule.provideUnauthenticatedOkHttpClient exactly: a
        // plain client with no interceptor, no authenticator -- AuthApi's real
        // configuration, not a stand-in.
        val unauthenticatedClient = OkHttpClient.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(unauthenticatedClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val authApi = retrofit.create(AuthApi::class.java)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"success":true,"data":{"accessToken":"a","refreshToken":"b","user":{"id":1,"username":"alice","email":"a@example.com"}}}""")
                .build()
        )

        runBlocking {
            authApi.refresh(RefreshTokenRequest(refreshToken = "the-real-refresh-token-value"))
        }

        val recorded = server.takeRequest()
        assertNull("a refresh request must never carry an Authorization header", recorded.headers["Authorization"])
        val body = recorded.body!!.utf8()
        assertEquals("""{"refreshToken":"the-real-refresh-token-value"}""", body)
    }
}
