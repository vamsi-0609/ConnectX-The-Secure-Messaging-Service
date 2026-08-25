package com.connectx.app.data.remote.connection

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.connection.model.ConnectionRequestDto
import com.connectx.app.data.remote.connection.model.SendConnectionRequestDto
import com.connectx.app.data.remote.connection.model.UserConnectionDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.3 Connection DTOs match the
 * actual backend JSON contract, read directly from connectx-backend source
 * (connection/dto/SendConnectionRequestDto.java, ConnectionRequestDto.java,
 * UserConnectionDto.java). No network call, no Retrofit, no OkHttp.
 */
class ConnectionContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `SendConnectionRequestDto encodes with exact backend field name`() {
        val request = SendConnectionRequestDto(recipientId = 7L)
        assertEquals("""{"recipientId":7}""", json.encodeToString(request))
    }

    @Test
    fun `ApiResponse of ConnectionRequestDto decodes a realistic pending request`() {
        // Shaped like ConnectionController#sendRequest -> ApiResponse.success(..., ConnectionRequestDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Connection request sent",
              "data": {
                "id": 101,
                "requesterId": 42,
                "requesterUsername": "vamsi",
                "requesterDisplayName": "Vamsi",
                "requesterProfileImageUrl": null,
                "recipientId": 7,
                "recipientUsername": "ananya",
                "recipientDisplayName": "Ananya",
                "recipientProfileImageUrl": null,
                "status": "PENDING",
                "createdAt": "2026-08-24T11:00:00Z",
                "respondedAt": null
              },
              "timestamp": "2026-08-24T11:00:01Z"
            }
        """.trimIndent()

        val response: ApiResponse<ConnectionRequestDto> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val req = response.data!!
        assertEquals(101L, req.id)
        assertEquals("PENDING", req.status)
        assertNull(req.respondedAt)
        assertEquals("ananya", req.recipientUsername)
    }

    @Test
    fun `ApiResponse of List ConnectionRequestDto decodes pending-incoming list`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Pending incoming connection requests",
              "data": [
                {
                  "id": 101,
                  "requesterId": 7,
                  "requesterUsername": "ananya",
                  "requesterDisplayName": "Ananya",
                  "requesterProfileImageUrl": null,
                  "recipientId": 42,
                  "recipientUsername": "vamsi",
                  "recipientDisplayName": "Vamsi",
                  "recipientProfileImageUrl": null,
                  "status": "PENDING",
                  "createdAt": "2026-08-24T11:05:00Z",
                  "respondedAt": null
                }
              ],
              "timestamp": "2026-08-24T11:05:01Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<ConnectionRequestDto>> = json.decodeFromString(rawJson)

        assertEquals(1, response.data!!.size)
        assertEquals(7L, response.data!![0].requesterId)
    }

    @Test
    fun `ApiResponse of List UserConnectionDto decodes an established connection`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Your connections",
              "data": [
                {
                  "id": 55,
                  "connectedUserId": 7,
                  "connectedUsername": "ananya",
                  "connectedDisplayName": "Ananya",
                  "connectedProfileImageUrl": null,
                  "createdAt": "2026-08-20T09:00:00Z"
                }
              ],
              "timestamp": "2026-08-24T11:06:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<UserConnectionDto>> = json.decodeFromString(rawJson)

        assertEquals(1, response.data!!.size)
        assertEquals("ananya", response.data!![0].connectedUsername)
    }

    @Test
    fun `ApiResponse of String decodes removeConnection response`() {
        val rawJson = """
            {"success":true,"code":"SUCCESS","message":"Connection removed","data":"Removed","timestamp":"2026-08-24T11:07:00Z"}
        """.trimIndent()

        val response: ApiResponse<String> = json.decodeFromString(rawJson)

        assertEquals("Removed", response.data)
    }
}
