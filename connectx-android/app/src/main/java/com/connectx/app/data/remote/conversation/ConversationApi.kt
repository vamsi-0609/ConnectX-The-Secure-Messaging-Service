package com.connectx.app.data.remote.conversation

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.conversation.model.ConversationDto
import com.connectx.app.data.remote.conversation.model.CreateDirectConversationDto
import com.connectx.app.data.remote.conversation.model.MuteConversationRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Full Android-side contract for the backend's Conversation API, verified directly
 * against com.connectx.conversation.controller.ConversationController -- 13
 * endpoints, matching the N2.0 inspection's reported count exactly (no discrepancy
 * this time; N2.2's User undercount was the exception, not the rule).
 *
 * No pagination exists on this controller -- GET /conversations returns a plain
 * List<ConversationDto>, not a Page/Slice/cursor structure.
 *
 * Contract only -- no call site exists anywhere yet. All 13 endpoints require
 * authentication on the backend; no Authorization header handling is added here
 * (deferred to N3). No MessageApi/message models were introduced -- ConversationDto's
 * last-message fields are flat scalars, not a nested message object (see
 * ConversationDto.kt's doc comment).
 */
interface ConversationApi {

    @GET("api/v1/conversations")
    suspend fun getConversations(): ApiResponse<List<ConversationDto>>

    @POST("api/v1/conversations/direct")
    suspend fun createOrGetDirectConversation(
        @Body request: CreateDirectConversationDto
    ): ApiResponse<ConversationDto>

    @GET("api/v1/conversations/{conversationId}")
    suspend fun getConversationById(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @DELETE("api/v1/conversations/{conversationId}")
    suspend fun deleteConversationForUser(@Path("conversationId") conversationId: Long): ApiResponse<String>

    @POST("api/v1/conversations/{conversationId}/clear")
    suspend fun clearConversationForUser(@Path("conversationId") conversationId: Long): ApiResponse<String>

    @POST("api/v1/conversations/{conversationId}/pin")
    suspend fun pinConversation(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/unpin")
    suspend fun unpinConversation(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    /** Backend body is optional (@RequestBody(required = false)) -- pass null for "mute indefinitely". */
    @POST("api/v1/conversations/{conversationId}/mute")
    suspend fun muteConversation(
        @Path("conversationId") conversationId: Long,
        @Body request: MuteConversationRequest?
    ): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/unmute")
    suspend fun unmuteConversation(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/archive")
    suspend fun archiveConversation(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/unarchive")
    suspend fun unarchiveConversation(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/mark-unread")
    suspend fun markConversationUnread(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>

    @POST("api/v1/conversations/{conversationId}/mark-read")
    suspend fun markConversationRead(@Path("conversationId") conversationId: Long): ApiResponse<ConversationDto>
}
