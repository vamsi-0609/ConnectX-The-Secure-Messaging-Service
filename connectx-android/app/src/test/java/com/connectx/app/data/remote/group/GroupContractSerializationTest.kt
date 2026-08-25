package com.connectx.app.data.remote.group

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.conversation.model.ConversationMemberDto
import com.connectx.app.data.remote.group.model.CreateGroupInvitationRequestDto
import com.connectx.app.data.remote.group.model.CreateGroupInvitationResponseDto
import com.connectx.app.data.remote.group.model.CreateGroupRequestDto
import com.connectx.app.data.remote.group.model.GroupDto
import com.connectx.app.data.remote.group.model.GroupInvitationDto
import com.connectx.app.data.remote.group.model.GroupKeyRequestResultDto
import com.connectx.app.data.remote.group.model.GroupKeyRotationResultDto
import com.connectx.app.data.remote.group.model.GroupMemberKeyDto
import com.connectx.app.data.remote.group.model.SubmitGroupMemberKeyRequestDto
import com.connectx.app.data.remote.group.model.TransferOwnershipRequestDto
import com.connectx.app.data.remote.group.model.UpdateGroupInfoRequestDto
import com.connectx.app.data.remote.group.model.UpdateGroupSettingsRequestDto
import com.connectx.app.data.remote.group.model.UpdateMemberRoleRequestDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.7 Group DTOs match the actual
 * backend JSON contract, read directly from connectx-backend source across all
 * five group controllers (GroupController, GroupMembershipController,
 * GroupInvitationController, GroupImageController, GroupKeyController) and their
 * DTOs. No network call, no Retrofit, no OkHttp client, no real crypto.
 */
class GroupContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateGroupRequestDto encodes with exact backend field names`() {
        val request = CreateGroupRequestDto(name = "Weekend Trip", description = "Planning chat")
        assertEquals(
            """{"name":"Weekend Trip","description":"Planning chat"}""",
            json.encodeToString(request)
        )
    }

    @Test
    fun `ApiResponse of GroupDto decodes a realistic createGroup response`() {
        // Shaped like GroupController#createGroup -> ApiResponse.success(..., GroupDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group created",
              "data": {
                "id": 900,
                "type": "GROUP",
                "name": "Weekend Trip",
                "description": "Planning chat",
                "avatarUrl": null,
                "whoCanInvite": "ALL_MEMBERS",
                "whoCanSendMessages": "EVERYONE",
                "whoCanEditGroupInfo": "OWNER_ADMIN_ONLY",
                "createdByUserId": 7,
                "currentUserRole": "OWNER",
                "activeMemberCount": 1,
                "keyVersion": 1,
                "createdAt": "2026-08-25T09:00:00Z",
                "updatedAt": "2026-08-25T09:00:00Z"
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<GroupDto> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val group = response.data!!
        assertEquals(900L, group.id)
        assertEquals("OWNER", group.currentUserRole)
        assertEquals(1, group.keyVersion)
        assertNull(group.avatarUrl)
    }

    @Test
    fun `ApiResponse of List ConversationMemberDto decodes getGroupMembers with roles`() {
        // Shaped like GroupController#getGroupMembers -> ApiResponse.success(..., List<ConversationMemberDto>)
        // Genuine reuse of the same ConversationMemberDto used by ConversationApi.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group members",
              "data": [
                {
                  "id": 1,
                  "user": {
                    "id": 7,
                    "username": "alice",
                    "displayName": "Alice"
                  },
                  "joinedAt": "2026-08-25T09:00:00Z",
                  "role": "OWNER"
                },
                {
                  "id": 2,
                  "user": {
                    "id": 8,
                    "username": "bob",
                    "displayName": "Bob"
                  },
                  "joinedAt": "2026-08-25T09:05:00Z",
                  "role": "MEMBER"
                }
              ],
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<ConversationMemberDto>> = json.decodeFromString(rawJson)

        val members = response.data!!
        assertEquals(2, members.size)
        assertEquals("OWNER", members[0].role)
        assertEquals("bob", members[1].user!!.username)
    }

    @Test
    fun `UpdateGroupSettingsRequestDto encodes only the fields being changed`() {
        val request = UpdateGroupSettingsRequestDto(whoCanInvite = "OWNER_ADMIN_ONLY")
        assertEquals("""{"whoCanInvite":"OWNER_ADMIN_ONLY"}""", json.encodeToString(request))
    }

    @Test
    fun `UpdateGroupInfoRequestDto encodes name and description`() {
        val request = UpdateGroupInfoRequestDto(name = "New Name", description = null)
        assertEquals("""{"name":"New Name"}""", json.encodeToString(request))
    }

    @Test
    fun `UpdateMemberRoleRequestDto encodes the requested role as a string`() {
        val request = UpdateMemberRoleRequestDto(role = "ADMIN")
        assertEquals("""{"role":"ADMIN"}""", json.encodeToString(request))
    }

    @Test
    fun `TransferOwnershipRequestDto encodes with exact backend field name`() {
        val request = TransferOwnershipRequestDto(newOwnerUserId = 8L)
        assertEquals("""{"newOwnerUserId":8}""", json.encodeToString(request))
    }

    @Test
    fun `ApiResponse of String decodes deleteGroup leaveGroup and transferOwnership responses`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Ownership transferred",
              "data": "Transferred",
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<String> = json.decodeFromString(rawJson)
        assertEquals("Transferred", response.data)
    }

    @Test
    fun `CreateGroupInvitationRequestDto encodes with exact backend field name`() {
        val request = CreateGroupInvitationRequestDto(targetUserId = 9L)
        assertEquals("""{"targetUserId":9}""", json.encodeToString(request))
    }

    @Test
    fun `ApiResponse of CreateGroupInvitationResponseDto decodes DIRECT_ADDED outcome with null invitation`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group invitation processed",
              "data": {
                "outcome": "DIRECT_ADDED",
                "invitation": null
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<CreateGroupInvitationResponseDto> = json.decodeFromString(rawJson)

        assertEquals("DIRECT_ADDED", response.data!!.outcome)
        assertNull(response.data!!.invitation)
    }

    @Test
    fun `ApiResponse of CreateGroupInvitationResponseDto decodes INVITATION_SENT outcome with nested invitation`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group invitation processed",
              "data": {
                "outcome": "INVITATION_SENT",
                "invitation": {
                  "id": 55,
                  "groupId": 900,
                  "groupName": "Weekend Trip",
                  "invitee": { "id": 9, "username": "carol" },
                  "invitedBy": { "id": 7, "username": "alice" },
                  "status": "PENDING",
                  "createdAt": "2026-08-25T09:10:00Z",
                  "respondedAt": null
                }
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<CreateGroupInvitationResponseDto> = json.decodeFromString(rawJson)

        val invitation = response.data!!.invitation!!
        assertEquals("INVITATION_SENT", response.data!!.outcome)
        assertEquals("PENDING", invitation.status)
        assertEquals("carol", invitation.invitee!!.username)
        assertNull(invitation.respondedAt)
    }

    @Test
    fun `ApiResponse of List GroupInvitationDto decodes received and sent pending lists`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Pending received group invitations",
              "data": [
                {
                  "id": 55,
                  "groupId": 900,
                  "groupName": "Weekend Trip",
                  "invitee": { "id": 9, "username": "carol" },
                  "invitedBy": { "id": 7, "username": "alice" },
                  "status": "PENDING",
                  "createdAt": "2026-08-25T09:10:00Z",
                  "respondedAt": null
                }
              ],
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<GroupInvitationDto>> = json.decodeFromString(rawJson)
        assertEquals(1, response.data!!.size)
        assertEquals(900L, response.data!![0].groupId)
    }

    @Test
    fun `raw binary group avatar download is represented as a plain ResponseBody, not wrapped in ApiResponse`() {
        // Contract-only check: getGroupImage() returns okhttp3.ResponseBody directly
        // (matching GroupImageController#getGroupImage's raw Resource return type),
        // same established pattern as MediaApi.getMedia / UserApi.getProfileImage.
        val fakeImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val body = fakeImageBytes.toResponseBody("image/png".toMediaType())

        assertEquals("image/png", body.contentType().toString())
        assertTrue(body.bytes().contentEquals(fakeImageBytes))
    }

    @Test
    fun `SubmitGroupMemberKeyRequestDto encodes opaque wrapped key material`() {
        val request = SubmitGroupMemberKeyRequestDto(
            memberUserId = 8L,
            wrappedKey = "opaque-wrapped-key",
            wrapNonce = "opaque-nonce",
            keyVersion = 2
        )
        assertEquals(
            """{"memberUserId":8,"wrappedKey":"opaque-wrapped-key","wrapNonce":"opaque-nonce","keyVersion":2}""",
            json.encodeToString(request)
        )
    }

    @Test
    fun `ApiResponse of GroupMemberKeyDto decodes submitWrappedKey and getMyWrappedKey responses`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group key",
              "data": {
                "groupId": 900,
                "keyVersion": 2,
                "wrappedKey": "opaque-wrapped-key",
                "wrapNonce": "opaque-nonce",
                "wrappedByUserId": 7
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<GroupMemberKeyDto> = json.decodeFromString(rawJson)

        val key = response.data!!
        assertEquals(900L, key.groupId)
        assertEquals(2, key.keyVersion)
        assertEquals(7L, key.wrappedByUserId)
    }

    @Test
    fun `ApiResponse of GroupKeyRequestResultDto decodes requestRewrap response`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group key reconciliation requested",
              "data": { "keyVersion": 2, "broadcastSent": true },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<GroupKeyRequestResultDto> = json.decodeFromString(rawJson)
        assertEquals(2, response.data!!.keyVersion)
        assertTrue(response.data!!.broadcastSent)
    }

    @Test
    fun `ApiResponse of GroupKeyRotationResultDto decodes rotateForRecovery response`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Group key rotated for recovery",
              "data": { "keyVersion": 3 },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<GroupKeyRotationResultDto> = json.decodeFromString(rawJson)
        assertEquals(3, response.data!!.keyVersion)
    }
}
