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
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Full Android-side contract for the backend's Group API, verified directly
 * against ALL FIVE current group controllers -- 23 endpoints total, matching the
 * N2.0 inspection's reported count exactly (no discrepancy this time; only
 * N2.2's User and N2.5's Message groups were undercounted):
 *
 *  - GroupController                (8): create, get details, get members,
 *                                        update settings, update info, upload/
 *                                        remove avatar, delete group
 *  - GroupMembershipController      (4): change role, remove member, leave,
 *                                        transfer ownership
 *  - GroupInvitationController      (6): create/accept/reject/cancel invitation,
 *                                        received/sent pending lists
 *  - GroupImageController           (1): raw group avatar bytes
 *  - GroupKeyController             (4): submit wrapped key, get my wrapped key,
 *                                        request rewrap, rotate for recovery
 *
 * All five controllers are modeled in a single interface (one Group domain, per
 * N2.7 instructions), even though GroupImageController lives under a distinct
 * base path (/api/v1/group-images, not /api/v1/groups) -- kept in this file
 * rather than a separate interface since it is still conceptually part of the
 * Group contract.
 *
 * getGroupMembers/changeRole reuse ConversationMemberDto (data/remote/conversation/
 * model/) -- the backend's GroupController#getGroupMembers and
 * GroupMembershipController#changeRole both literally return
 * List<ConversationMemberDto>/ConversationMemberDto (com.connectx.conversation.dto),
 * the exact same class used by ConversationApi, not a similarly-named-but-distinct
 * shape. No duplicate model was created for it.
 *
 * getGroupImage mirrors MediaApi.getMedia / UserApi.getProfileImage's established
 * raw-binary pattern: ResponseBody + @Streaming, never ApiResponse-wrapped.
 *
 * Contract only -- no call site exists anywhere yet, no group management UI, no
 * WebSocket/realtime events, no cryptographic behavior (wrappedKey/wrapNonce are
 * opaque scalar fields only, per N7's boundary). All 23 endpoints require
 * authentication on the backend; no Authorization header handling is added here
 * (deferred to N3).
 */
interface GroupApi {

    // ---- GroupController ----

    @POST("api/v1/groups")
    suspend fun createGroup(@Body request: CreateGroupRequestDto): ApiResponse<GroupDto>

    @GET("api/v1/groups/{groupId}")
    suspend fun getGroupDetails(@Path("groupId") groupId: Long): ApiResponse<GroupDto>

    @GET("api/v1/groups/{groupId}/members")
    suspend fun getGroupMembers(@Path("groupId") groupId: Long): ApiResponse<List<ConversationMemberDto>>

    @PATCH("api/v1/groups/{groupId}/settings")
    suspend fun updateSettings(
        @Path("groupId") groupId: Long,
        @Body request: UpdateGroupSettingsRequestDto
    ): ApiResponse<GroupDto>

    @PATCH("api/v1/groups/{groupId}/info")
    suspend fun updateGroupInfo(
        @Path("groupId") groupId: Long,
        @Body request: UpdateGroupInfoRequestDto
    ): ApiResponse<GroupDto>

    @Multipart
    @POST("api/v1/groups/{groupId}/avatar")
    suspend fun uploadAvatar(
        @Path("groupId") groupId: Long,
        @Part file: MultipartBody.Part
    ): ApiResponse<GroupDto>

    @DELETE("api/v1/groups/{groupId}/avatar")
    suspend fun removeAvatar(@Path("groupId") groupId: Long): ApiResponse<GroupDto>

    @DELETE("api/v1/groups/{groupId}")
    suspend fun deleteGroup(@Path("groupId") groupId: Long): ApiResponse<String>

    // ---- GroupMembershipController ----

    @PATCH("api/v1/groups/{groupId}/members/{userId}/role")
    suspend fun changeRole(
        @Path("groupId") groupId: Long,
        @Path("userId") userId: Long,
        @Body request: UpdateMemberRoleRequestDto
    ): ApiResponse<ConversationMemberDto>

    @DELETE("api/v1/groups/{groupId}/members/{userId}")
    suspend fun removeMember(
        @Path("groupId") groupId: Long,
        @Path("userId") userId: Long
    ): ApiResponse<String>

    @POST("api/v1/groups/{groupId}/leave")
    suspend fun leaveGroup(@Path("groupId") groupId: Long): ApiResponse<String>

    @POST("api/v1/groups/{groupId}/ownership/transfer")
    suspend fun transferOwnership(
        @Path("groupId") groupId: Long,
        @Body request: TransferOwnershipRequestDto
    ): ApiResponse<String>

    // ---- GroupInvitationController ----

    @POST("api/v1/groups/{groupId}/invitations")
    suspend fun createInvitation(
        @Path("groupId") groupId: Long,
        @Body request: CreateGroupInvitationRequestDto
    ): ApiResponse<CreateGroupInvitationResponseDto>

    @POST("api/v1/groups/invitations/{invitationId}/accept")
    suspend fun acceptInvitation(@Path("invitationId") invitationId: Long): ApiResponse<GroupInvitationDto>

    @POST("api/v1/groups/invitations/{invitationId}/reject")
    suspend fun rejectInvitation(@Path("invitationId") invitationId: Long): ApiResponse<GroupInvitationDto>

    @POST("api/v1/groups/invitations/{invitationId}/cancel")
    suspend fun cancelInvitation(@Path("invitationId") invitationId: Long): ApiResponse<GroupInvitationDto>

    @GET("api/v1/groups/invitations/received")
    suspend fun getReceivedPendingInvitations(): ApiResponse<List<GroupInvitationDto>>

    @GET("api/v1/groups/invitations/sent")
    suspend fun getSentPendingInvitations(): ApiResponse<List<GroupInvitationDto>>

    // ---- GroupImageController ----

    /**
     * Raw binary endpoint (GroupImageController#getGroupImage) -- returns image
     * bytes directly, never wrapped in ApiResponse. Distinct route/namespace
     * (group-images, not group avatar's own /{groupId}/avatar mutating routes
     * above) mirroring the backend's deliberate separation.
     */
    @Streaming
    @GET("api/v1/group-images/{groupId}")
    suspend fun getGroupImage(@Path("groupId") groupId: Long): ResponseBody

    // ---- GroupKeyController ----

    @POST("api/v1/groups/{groupId}/keys")
    suspend fun submitWrappedKey(
        @Path("groupId") groupId: Long,
        @Body request: SubmitGroupMemberKeyRequestDto
    ): ApiResponse<GroupMemberKeyDto>

    @GET("api/v1/groups/{groupId}/keys/me")
    suspend fun getMyWrappedKey(@Path("groupId") groupId: Long): ApiResponse<GroupMemberKeyDto>

    @POST("api/v1/groups/{groupId}/keys/request")
    suspend fun requestRewrap(@Path("groupId") groupId: Long): ApiResponse<GroupKeyRequestResultDto>

    @POST("api/v1/groups/{groupId}/keys/rotate-for-recovery")
    suspend fun rotateForRecovery(@Path("groupId") groupId: Long): ApiResponse<GroupKeyRotationResultDto>
}
