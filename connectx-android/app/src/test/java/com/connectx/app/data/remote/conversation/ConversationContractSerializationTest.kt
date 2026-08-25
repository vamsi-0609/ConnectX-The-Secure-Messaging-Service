package com.connectx.app.data.remote.conversation

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.conversation.model.ConversationDto
import com.connectx.app.data.remote.conversation.model.CreateDirectConversationDto
import com.connectx.app.data.remote.conversation.model.MuteConversationRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.4 Conversation DTOs match the
 * actual backend JSON contract, read directly from connectx-backend source
 * (conversation/dto/CreateDirectConversationDto.java, ConversationDto.java,
 * ConversationMemberDto.java, and ConversationController's optional mute body).
 * No network call, no Retrofit, no OkHttp.
 */
class ConversationContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateDirectConversationDto encodes with exact backend field name`() {
        val request = CreateDirectConversationDto(userId = 7L)
        assertEquals("""{"userId":7}""", json.encodeToString(request))
    }

    @Test
    fun `MuteConversationRequest encodes with or without mutedUntil`() {
        assertEquals(
            """{"mutedUntil":"2026-09-01T00:00:00Z"}""",
            json.encodeToString(MuteConversationRequest(mutedUntil = "2026-09-01T00:00:00Z"))
        )
        // No key at all when null, matching explicitNulls = false -- mirrors the
        // backend's tolerance for an absent/empty body (mutes indefinitely).
        assertEquals("{}", json.encodeToString(MuteConversationRequest(mutedUntil = null)))
    }

    @Test
    fun `ApiResponse of List ConversationDto decodes a realistic DIRECT conversation with nested member`() {
        // Shaped like ConversationController#getConversations -> ApiResponse.success(..., List<ConversationDto>)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User conversations",
              "data": [
                {
                  "id": 501,
                  "type": "DIRECT",
                  "createdAt": "2026-08-01T00:00:00Z",
                  "updatedAt": "2026-08-24T10:00:00Z",
                  "members": [
                    {
                      "id": 1,
                      "user": {
                        "id": 7,
                        "username": "ananya",
                        "displayName": "Ananya",
                        "profileImageUrl": null,
                        "status": "ONLINE",
                        "lastSeenAt": null,
                        "createdAt": "2026-01-05T00:00:00Z",
                        "profilePhotoVisibility": "EVERYONE"
                      },
                      "joinedAt": "2026-08-01T00:00:00Z",
                      "lastReadMessageId": 999,
                      "pinned": false,
                      "pinnedAt": null,
                      "mutedUntil": null,
                      "muted": false,
                      "archived": false,
                      "archivedAt": null,
                      "manuallyMarkedUnread": false,
                      "role": null
                    }
                  ],
                  "lastMessageId": 999,
                  "lastMessageSenderUserId": 7,
                  "lastMessageSentAt": "2026-08-24T10:00:00Z",
                  "lastMessageDeletedForEveryone": false,
                  "lastMessageType": "TEXT",
                  "lastMessageCaption": null,
                  "pinned": true,
                  "pinnedAt": "2026-08-20T00:00:00Z",
                  "muted": false,
                  "mutedUntil": null,
                  "archived": false,
                  "archivedAt": null,
                  "manuallyMarkedUnread": false
                }
              ],
              "timestamp": "2026-08-24T11:20:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<ConversationDto>> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val conversation = response.data!![0]
        assertEquals("DIRECT", conversation.type)
        assertTrue(conversation.pinned)
        assertEquals(1, conversation.members!!.size)
        val member = conversation.members!![0]
        assertNull(member.role)
        assertEquals("ananya", member.user!!.username)
        assertEquals("TEXT", conversation.lastMessageType)
    }

    @Test
    fun `ApiResponse of ConversationDto decodes a GROUP member with a role and no last message`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Direct conversation retrieved or created",
              "data": {
                "id": 502,
                "type": "GROUP",
                "createdAt": "2026-08-10T00:00:00Z",
                "updatedAt": "2026-08-10T00:00:00Z",
                "members": [
                  {
                    "id": 2,
                    "user": {
                      "id": 42,
                      "username": "vamsi",
                      "displayName": "Vamsi",
                      "profileImageUrl": null,
                      "status": "ONLINE",
                      "lastSeenAt": null,
                      "createdAt": "2026-01-01T00:00:00Z",
                      "profilePhotoVisibility": "EVERYONE"
                    },
                    "joinedAt": "2026-08-10T00:00:00Z",
                    "lastReadMessageId": null,
                    "pinned": false,
                    "pinnedAt": null,
                    "mutedUntil": null,
                    "muted": false,
                    "archived": false,
                    "archivedAt": null,
                    "manuallyMarkedUnread": false,
                    "role": "OWNER"
                  }
                ],
                "lastMessageId": null,
                "lastMessageSenderUserId": null,
                "lastMessageSentAt": null,
                "lastMessageDeletedForEveryone": false,
                "lastMessageType": null,
                "lastMessageCaption": null,
                "pinned": false,
                "pinnedAt": null,
                "muted": false,
                "mutedUntil": null,
                "archived": false,
                "archivedAt": null,
                "manuallyMarkedUnread": false
              },
              "timestamp": "2026-08-24T11:21:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<ConversationDto> = json.decodeFromString(rawJson)

        val conversation = response.data!!
        assertEquals("GROUP", conversation.type)
        assertEquals("OWNER", conversation.members!![0].role)
        assertNull(conversation.lastMessageId)
        assertNull(conversation.lastMessageType)
    }

    @Test
    fun `ApiResponse of String decodes deleteConversationForUser response`() {
        val rawJson = """
            {"success":true,"code":"SUCCESS","message":"Conversation deleted for current user","data":"Deleted","timestamp":"2026-08-24T11:22:00Z"}
        """.trimIndent()

        val response: ApiResponse<String> = json.decodeFromString(rawJson)

        assertEquals("Deleted", response.data)
    }

    @Test
    fun `ApiResponse of ConversationDto decodes an empty-members conversation without crashing`() {
        // Defensive case: members as an empty list, not null -- confirms empty-collection handling.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Conversation details",
              "data": {
                "id": 503,
                "type": "GROUP",
                "createdAt": "2026-08-24T00:00:00Z",
                "updatedAt": "2026-08-24T00:00:00Z",
                "members": [],
                "lastMessageId": null,
                "lastMessageSenderUserId": null,
                "lastMessageSentAt": null,
                "lastMessageDeletedForEveryone": false,
                "lastMessageType": null,
                "lastMessageCaption": null,
                "pinned": false,
                "pinnedAt": null,
                "muted": false,
                "mutedUntil": null,
                "archived": false,
                "archivedAt": null,
                "manuallyMarkedUnread": false
              },
              "timestamp": "2026-08-24T11:23:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<ConversationDto> = json.decodeFromString(rawJson)

        assertTrue(response.data!!.members!!.isEmpty())
    }
}
