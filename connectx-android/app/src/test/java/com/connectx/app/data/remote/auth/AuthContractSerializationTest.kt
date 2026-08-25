package com.connectx.app.data.remote.auth

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.core.network.model.ErrorResponse
import com.connectx.app.data.remote.auth.model.AuthResponse
import com.connectx.app.data.remote.auth.model.LoginRequest
import com.connectx.app.data.remote.auth.model.RegisterRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.1 DTOs match the actual backend
 * JSON contract, as read directly from connectx-backend source
 * (auth/dto/RegisterRequest.java, LoginRequest.java, AuthResponse.java,
 * user/dto/UserDto.java, common/response/ApiResponse.java, ErrorResponse.java).
 *
 * No network call, no Retrofit, no OkHttp involved -- pure kotlinx.serialization
 * Json encode/decode against hand-written JSON strings shaped exactly like real
 * backend responses/requests.
 */
class AuthContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `RegisterRequest encodes with exact backend field names`() {
        val request = RegisterRequest(
            username = "vamsi",
            email = "vamsi@example.com",
            password = "hunter2x",
            displayName = "Vamsi"
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"username\":\"vamsi\""))
        assertTrue(encoded.contains("\"email\":\"vamsi@example.com\""))
        assertTrue(encoded.contains("\"password\":\"hunter2x\""))
        assertTrue(encoded.contains("\"displayName\":\"Vamsi\""))
    }

    @Test
    fun `RegisterRequest omits null displayName when explicitNulls is false`() {
        val request = RegisterRequest(username = "vamsi", email = "v@example.com", password = "hunter2x")
        val encoded = json.encodeToString(request)
        assertTrue(!encoded.contains("displayName"))
    }

    @Test
    fun `LoginRequest encodes with exact backend field names`() {
        val request = LoginRequest(usernameOrEmail = "vamsi", password = "hunter2x")
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"usernameOrEmail\":\"vamsi\""))
        assertTrue(encoded.contains("\"password\":\"hunter2x\""))
    }

    @Test
    fun `ApiResponse of AuthResponse decodes a realistic backend login response`() {
        // Shaped exactly like AuthController#login -> ApiResponse.success("Login successful", AuthResponse)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Login successful",
              "data": {
                "accessToken": "eyJhbGciOiJIUzI1NiJ9.fake.access",
                "refreshToken": "eyJhbGciOiJIUzI1NiJ9.fake.refresh",
                "tokenType": "Bearer",
                "user": {
                  "id": 42,
                  "username": "vamsi",
                  "email": "vamsi@example.com",
                  "displayName": "Vamsi",
                  "profileImageUrl": null,
                  "status": "ONLINE",
                  "lastSeenAt": "2026-08-24T10:15:30Z",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "profilePhotoVisibility": "EVERYONE",
                  "groupAddPrivacy": "ANYONE"
                }
              },
              "timestamp": "2026-08-24T10:15:31Z"
            }
        """.trimIndent()

        val response: ApiResponse<AuthResponse> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        assertEquals("SUCCESS", response.code)
        val auth = response.data!!
        assertEquals("eyJhbGciOiJIUzI1NiJ9.fake.access", auth.accessToken)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.fake.refresh", auth.refreshToken)
        assertEquals("Bearer", auth.tokenType)
        assertEquals(42L, auth.user.id)
        assertEquals("vamsi", auth.user.username)
        assertNull(auth.user.profileImageUrl)
        assertEquals("EVERYONE", auth.user.profilePhotoVisibility)
    }

    @Test
    fun `ApiResponse decodes a realistic backend error via ErrorResponse shape`() {
        // Shaped exactly like GlobalExceptionHandler's ErrorResponse for a 401.
        val rawJson = """
            {
              "timestamp": "2026-08-24T10:16:00Z",
              "status": 401,
              "code": "UNAUTHORIZED",
              "message": "Full authentication is required to access this resource",
              "path": "/api/v1/users/me"
            }
        """.trimIndent()

        val error: ErrorResponse = json.decodeFromString(rawJson)

        assertEquals(401, error.status)
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals("/api/v1/users/me", error.path)
    }
}
