package com.connectx.app.data.remote.block

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.block.model.UserBlockDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.3 Block DTO matches the actual
 * backend JSON contract, read directly from connectx-backend source
 * (block/dto/UserBlockDto.java). No network call, no Retrofit, no OkHttp.
 */
class BlockContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `ApiResponse of UserBlockDto decodes a realistic blockUser response`() {
        // Shaped like BlockController#blockUser -> ApiResponse.success("User blocked", UserBlockDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User blocked",
              "data": {
                "id": 9,
                "blockedUserId": 13,
                "blockedUsername": "spammer",
                "blockedDisplayName": "Spammer",
                "blockedProfileImageUrl": null,
                "createdAt": "2026-08-24T11:10:00Z"
              },
              "timestamp": "2026-08-24T11:10:01Z"
            }
        """.trimIndent()

        val response: ApiResponse<UserBlockDto> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        assertEquals(13L, response.data!!.blockedUserId)
        assertEquals("spammer", response.data!!.blockedUsername)
        assertNull(response.data!!.blockedProfileImageUrl)
    }

    @Test
    fun `ApiResponse of List UserBlockDto decodes getMyBlocks response`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Your blocked users",
              "data": [
                {
                  "id": 9,
                  "blockedUserId": 13,
                  "blockedUsername": "spammer",
                  "blockedDisplayName": "Spammer",
                  "blockedProfileImageUrl": null,
                  "createdAt": "2026-08-24T11:10:00Z"
                }
              ],
              "timestamp": "2026-08-24T11:11:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<UserBlockDto>> = json.decodeFromString(rawJson)

        assertEquals(1, response.data!!.size)
        assertEquals(9L, response.data!![0].id)
    }

    @Test
    fun `ApiResponse of String decodes unblockUser response`() {
        val rawJson = """
            {"success":true,"code":"SUCCESS","message":"User unblocked","data":"Unblocked","timestamp":"2026-08-24T11:12:00Z"}
        """.trimIndent()

        val response: ApiResponse<String> = json.decodeFromString(rawJson)

        assertEquals("Unblocked", response.data)
    }
}
