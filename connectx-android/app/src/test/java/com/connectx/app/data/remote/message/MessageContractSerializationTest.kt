package com.connectx.app.data.remote.message

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.message.model.AddReactionRequest
import com.connectx.app.data.remote.message.model.EditMessageRequest
import com.connectx.app.data.remote.message.model.MessageDto
import com.connectx.app.data.remote.message.model.PagedMessageResponseDto
import com.connectx.app.data.remote.message.model.SendMessageRequestDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.5 Message DTOs match the actual
 * backend JSON contract, read directly from connectx-backend source
 * (message/dto/SendMessageRequestDto.java, MessageDto.java, MessageReactionDto.java,
 * PagedMessageResponseDto.java). No network call, no Retrofit, no OkHttp.
 */
class MessageContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `SendMessageRequestDto encodes a minimal TEXT send with exact backend field names`() {
        val request = SendMessageRequestDto(
            conversationId = 501L,
            messageType = "TEXT",
            ciphertext = "opaque-ciphertext",
            nonce = "opaque-nonce",
            requestId = "req-1"
        )
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"conversationId\":501"))
        assertTrue(encoded.contains("\"messageType\":\"TEXT\""))
        assertTrue(encoded.contains("\"ciphertext\":\"opaque-ciphertext\""))
        assertTrue(encoded.contains("\"requestId\":\"req-1\""))
        // Omitted optional fields shouldn't appear at all (explicitNulls = false).
        assertFalse(encoded.contains("mediaId"))
        assertFalse(encoded.contains("latitude"))
    }

    @Test
    fun `AddReactionRequest and EditMessageRequest match the backend's raw map bodies`() {
        assertEquals("""{"reaction":"🔥"}""", json.encodeToString(AddReactionRequest(reaction = "🔥")))

        val edit = EditMessageRequest(ciphertext = "new-ciphertext", nonce = "new-nonce")
        val encoded = json.encodeToString(edit)
        assertTrue(encoded.contains("\"ciphertext\":\"new-ciphertext\""))
        assertTrue(encoded.contains("\"nonce\":\"new-nonce\""))
    }

    @Test
    fun `ApiResponse of MessageDto decodes a realistic sendMessage response with a reaction`() {
        // Shaped like MessageController#sendMessage -> ApiResponse.success(..., MessageDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Message sent successfully",
              "data": {
                "id": 9001,
                "conversationId": 501,
                "senderUserId": 42,
                "senderUsername": "vamsi",
                "senderDeviceId": null,
                "recipientDeviceId": null,
                "messageType": "TEXT",
                "mediaId": null,
                "caption": null,
                "latitude": null,
                "longitude": null,
                "locationLabel": null,
                "mimeType": null,
                "fileSizeBytes": null,
                "encryptionAlgorithm": "ECDH-P256+AES-256-GCM",
                "ciphertext": "opaque-ciphertext",
                "nonce": "opaque-nonce",
                "groupKeyVersion": null,
                "sentAt": "2026-08-24T12:00:00Z",
                "deliveredAt": null,
                "readAt": null,
                "deletedForEveryone": false,
                "replyToMessageId": null,
                "replyToSenderUsername": null,
                "replyToMessageType": null,
                "replyToCaption": null,
                "replyToDeleted": false,
                "reactions": [
                  {
                    "id": 1,
                    "messageId": 9001,
                    "userId": 7,
                    "username": "ananya",
                    "reaction": "🔥",
                    "createdAt": "2026-08-24T12:01:00Z"
                  }
                ],
                "editedAt": null,
                "forwarded": false,
                "pinnedAt": null,
                "pinnedByUserId": null,
                "pinnedByUsername": null,
                "starred": false,
                "mediaNonce": null
              },
              "timestamp": "2026-08-24T12:00:01Z"
            }
        """.trimIndent()

        val response: ApiResponse<MessageDto> = json.decodeFromString(rawJson)

        val msg = response.data!!
        assertEquals(9001L, msg.id)
        assertEquals("TEXT", msg.messageType)
        assertEquals(1, msg.reactions.size)
        assertEquals("ananya", msg.reactions[0].username)
        assertNull(msg.mediaId)
    }

    @Test
    fun `ApiResponse of MessageDto decodes a reply with a deleted reply target`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Message sent successfully",
              "data": {
                "id": 9002,
                "conversationId": 501,
                "senderUserId": 7,
                "senderUsername": "ananya",
                "messageType": "TEXT",
                "encryptionAlgorithm": "ECDH-P256+AES-256-GCM",
                "ciphertext": "reply-ciphertext",
                "nonce": "reply-nonce",
                "sentAt": "2026-08-24T12:05:00Z",
                "deletedForEveryone": false,
                "replyToMessageId": 9001,
                "replyToSenderUsername": "vamsi",
                "replyToMessageType": "TEXT",
                "replyToCaption": null,
                "replyToDeleted": true,
                "reactions": [],
                "forwarded": false,
                "starred": false
              },
              "timestamp": "2026-08-24T12:05:01Z"
            }
        """.trimIndent()

        val response: ApiResponse<MessageDto> = json.decodeFromString(rawJson)

        val msg = response.data!!
        assertEquals(9001L, msg.replyToMessageId)
        assertTrue(msg.replyToDeleted)
        assertTrue(msg.reactions.isEmpty())
    }

    @Test
    fun `ApiResponse of PagedMessageResponseDto decodes a cursor-paginated history page`() {
        // Shaped like MessageController#getConversationMessages -> ApiResponse.success(..., PagedMessageResponseDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Conversation message history",
              "data": {
                "messages": [
                  {
                    "id": 9001,
                    "conversationId": 501,
                    "senderUserId": 42,
                    "senderUsername": "vamsi",
                    "messageType": "TEXT",
                    "encryptionAlgorithm": "ECDH-P256+AES-256-GCM",
                    "ciphertext": "opaque-ciphertext",
                    "nonce": "opaque-nonce",
                    "sentAt": "2026-08-24T12:00:00Z",
                    "deletedForEveryone": false,
                    "replyToDeleted": false,
                    "reactions": [],
                    "forwarded": false,
                    "starred": false
                  }
                ],
                "hasMore": true,
                "nextCursor": 9001,
                "limit": 30
              },
              "timestamp": "2026-08-24T12:10:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<PagedMessageResponseDto> = json.decodeFromString(rawJson)

        val page = response.data!!
        assertTrue(page.hasMore)
        assertEquals(9001L, page.nextCursor)
        assertEquals(30, page.limit)
        assertEquals(1, page.messages.size)
    }

    @Test
    fun `ApiResponse of String decodes starMessage and deleteMessage responses`() {
        val starJson = """{"success":true,"code":"SUCCESS","message":"Message starred","data":"Starred","timestamp":"2026-08-24T12:11:00Z"}"""
        val deleteJson = """{"success":true,"code":"SUCCESS","message":"Message deleted","data":"Deleted","timestamp":"2026-08-24T12:12:00Z"}"""

        val starResponse: ApiResponse<String> = json.decodeFromString(starJson)
        val deleteResponse: ApiResponse<String> = json.decodeFromString(deleteJson)

        assertEquals("Starred", starResponse.data)
        assertEquals("Deleted", deleteResponse.data)
    }

    @Test
    fun `ApiResponse of nullable MessageDto data decodes a null pinned-message response`() {
        // getPinnedMessage's data is nullable when no message is pinned.
        val rawJson = """
            {"success":true,"code":"SUCCESS","message":"Pinned message","data":null,"timestamp":"2026-08-24T12:13:00Z"}
        """.trimIndent()

        val response: ApiResponse<MessageDto?> = json.decodeFromString(rawJson)

        assertNull(response.data)
    }
}
