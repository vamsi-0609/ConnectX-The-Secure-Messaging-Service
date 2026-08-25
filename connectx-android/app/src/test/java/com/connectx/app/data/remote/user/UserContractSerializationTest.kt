package com.connectx.app.data.remote.user

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.auth.model.UserDto
import com.connectx.app.data.remote.user.model.PublicUserDto
import com.connectx.app.data.remote.user.model.RequestEmailChangeOtpRequest
import com.connectx.app.data.remote.user.model.UserIdentityKeyDto
import com.connectx.app.data.remote.user.model.UserProfileUpdateDto
import com.connectx.app.data.remote.user.model.VerifyEmailChangeOtpRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.2 User DTOs match the actual
 * backend JSON contract, as read directly from connectx-backend source
 * (user/dto/PublicUserDto.java, UserProfileUpdateDto.java, UserIdentityKeyDto.java,
 * and UserController.java's raw Map<String,String> request bodies).
 *
 * No network call, no Retrofit, no OkHttp -- pure kotlinx.serialization Json
 * encode/decode against hand-written JSON strings shaped like real backend
 * requests/responses.
 */
class UserContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `ApiResponse of List PublicUserDto decodes a realistic search response`() {
        // Shaped like UserController#searchUsers -> ApiResponse.success("...", List<PublicUserDto>)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User search results",
              "data": [
                {
                  "id": 7,
                  "username": "ananya",
                  "displayName": "Ananya",
                  "profileImageUrl": null,
                  "status": "OFFLINE",
                  "lastSeenAt": "2026-08-20T09:00:00Z",
                  "createdAt": "2026-01-05T00:00:00Z",
                  "profilePhotoVisibility": "EVERYONE"
                }
              ],
              "timestamp": "2026-08-24T10:20:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<PublicUserDto>> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val users = response.data!!
        assertEquals(1, users.size)
        assertEquals(7L, users[0].id)
        assertEquals("ananya", users[0].username)
        assertNull(users[0].profileImageUrl)
    }

    @Test
    fun `ApiResponse of PublicUserDto decodes getUserById response`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User details",
              "data": {
                "id": 7,
                "username": "ananya",
                "displayName": "Ananya",
                "profileImageUrl": "https://app.myconnect.sbs/api/v1/profile-images/7",
                "status": "ONLINE",
                "lastSeenAt": null,
                "createdAt": "2026-01-05T00:00:00Z",
                "profilePhotoVisibility": "CONNECTIONS"
              },
              "timestamp": "2026-08-24T10:21:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<PublicUserDto> = json.decodeFromString(rawJson)

        assertEquals("CONNECTIONS", response.data!!.profilePhotoVisibility)
        assertNull(response.data!!.lastSeenAt)
    }

    @Test
    fun `ApiResponse of UserDto decodes getCurrentUser (me) response with email`() {
        // Confirms UserDto (shared with N2.1's AuthResponse.user) still round-trips
        // correctly when returned directly as GET /users/me's data payload.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Current user profile",
              "data": {
                "id": 42,
                "username": "vamsi",
                "email": "vamsi@example.com",
                "displayName": "Vamsi",
                "profileImageUrl": null,
                "status": "ONLINE",
                "lastSeenAt": "2026-08-24T10:00:00Z",
                "createdAt": "2026-01-01T00:00:00Z",
                "profilePhotoVisibility": "EVERYONE",
                "groupAddPrivacy": "ANYONE"
              },
              "timestamp": "2026-08-24T10:22:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<UserDto> = json.decodeFromString(rawJson)

        assertEquals("vamsi@example.com", response.data!!.email)
        assertEquals("ANYONE", response.data!!.groupAddPrivacy)
    }

    @Test
    fun `UserProfileUpdateDto encodes only provided fields with exact backend names`() {
        val request = UserProfileUpdateDto(displayName = "New Name", status = "BUSY")
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"displayName\":\"New Name\""))
        assertTrue(encoded.contains("\"status\":\"BUSY\""))
        assertTrue(!encoded.contains("username"))
        assertTrue(!encoded.contains("profilePhotoVisibility"))
    }

    @Test
    fun `RequestEmailChangeOtpRequest and VerifyEmailChangeOtpRequest match the backend's raw map body`() {
        val requestOtp = RequestEmailChangeOtpRequest(newEmail = "new@example.com")
        assertEquals("""{"newEmail":"new@example.com"}""", json.encodeToString(requestOtp))

        val verifyOtp = VerifyEmailChangeOtpRequest(newEmail = "new@example.com", otpCode = "123456")
        val encoded = json.encodeToString(verifyOtp)
        assertTrue(encoded.contains("\"newEmail\":\"new@example.com\""))
        assertTrue(encoded.contains("\"otpCode\":\"123456\""))
    }

    @Test
    fun `ApiResponse of UserIdentityKeyDto round-trips both key fields`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User identity key bundle",
              "data": {
                "masterPublicKey": "base64-public-key-data",
                "masterPrivateKey": "base64-encrypted-private-key-blob"
              },
              "timestamp": "2026-08-24T10:23:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<UserIdentityKeyDto> = json.decodeFromString(rawJson)

        assertEquals("base64-public-key-data", response.data!!.masterPublicKey)
        assertEquals("base64-encrypted-private-key-blob", response.data!!.masterPrivateKey)
    }
}