package com.connectx.app.core.network.auth

import com.connectx.app.data.local.auth.TokenPair
import com.connectx.app.data.local.auth.TokenStorage
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Real-OkHttp-layer verification of [AuthInterceptor] against a local
 * [MockWebServer] -- no Retrofit, no real backend, no network beyond
 * localhost. Confirms the actual header the server receives, not just that
 * some Kotlin function was called.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private var client: OkHttpClient? = null

    private class FakeTokenStorage(private var accessToken: String?) : TokenStorage {
        override fun saveTokens(accessToken: String, refreshToken: String) {}
        override fun getAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = null
        override fun getTokens(): TokenPair? = null
        override fun clearTokens() { accessToken = null }
        override fun hasTokens(): Boolean = false
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
    fun `attaches Authorization Bearer header when an access token is stored`() {
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStorage("stored-access-token")))
            .build()
        server.enqueue(MockResponse.Builder().code(200).build())

        client!!.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer stored-access-token", recorded.headers["Authorization"])
    }

    @Test
    fun `adds no Authorization header when no access token is stored`() {
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStorage(null)))
            .build()
        server.enqueue(MockResponse.Builder().code(200).build())

        client!!.newCall(Request.Builder().url(server.url("/public")).build()).execute().use {
            assertEquals(200, it.code)
        }

        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun `does not remove or alter unrelated existing headers`() {
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(FakeTokenStorage("stored-access-token")))
            .build()
        server.enqueue(MockResponse.Builder().code(200).build())

        client!!.newCall(
            Request.Builder()
                .url(server.url("/protected"))
                .header("X-Custom-Header", "custom-value")
                .build()
        ).execute().use { assertEquals(200, it.code) }

        val recorded = server.takeRequest()
        assertEquals("custom-value", recorded.headers["X-Custom-Header"])
        assertEquals("Bearer stored-access-token", recorded.headers["Authorization"])
    }
}
