package com.connectx.app.data.remote.push

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.push.model.PushSubscriptionRequestDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.7 Push DTOs match the actual
 * backend JSON contract, read directly from connectx-backend source
 * (push/dto/PushSubscriptionRequestDto.java,
 * push/controller/PushNotificationController.java). No network call, no
 * Retrofit, no OkHttp client. This is Web Push (VAPID), not Firebase Cloud
 * Messaging -- no FCM behavior implemented or tested here.
 */
class PushContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `PushSubscriptionRequestDto encodes the full W3C PushSubscription shape`() {
        val request = PushSubscriptionRequestDto(
            endpoint = "https://fcm.googleapis.com/fcm/send/abc123",
            expirationTime = null,
            keys = PushSubscriptionRequestDto.KeysDto(
                p256dh = "opaque-p256dh-key",
                auth = "opaque-auth-secret"
            )
        )
        assertEquals(
            """{"endpoint":"https://fcm.googleapis.com/fcm/send/abc123","keys":{"p256dh":"opaque-p256dh-key","auth":"opaque-auth-secret"}}""",
            json.encodeToString(request)
        )
    }

    @Test
    fun `PushSubscriptionRequestDto with only endpoint omits null fields`() {
        val request = PushSubscriptionRequestDto(endpoint = "https://push.example.com/xyz")
        assertEquals(
            """{"endpoint":"https://push.example.com/xyz"}""",
            json.encodeToString(request)
        )
    }

    @Test
    fun `ApiResponse of Map decodes getVapidPublicKey response`() {
        // Shaped like PushNotificationController#getVapidPublicKey ->
        // ApiResponse.success(..., Map.of("vapidPublicKey", publicKey))
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "VAPID public key retrieved",
              "data": {
                "vapidPublicKey": "opaque-vapid-public-key"
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<Map<String, String>> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        assertEquals("opaque-vapid-public-key", response.data!!["vapidPublicKey"])
    }

    @Test
    fun `ApiResponse of Unit decodes subscribe and unsubscribe responses with null data`() {
        // Shaped like PushNotificationController#subscribe / #unsubscribe ->
        // ApiResponse.success(..., null) -- backend declares ApiResponse<Void>.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Push notification subscription registered",
              "data": null,
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<Unit> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        assertNull(response.data)
    }
}
