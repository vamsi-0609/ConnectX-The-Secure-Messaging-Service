package com.connectx.app.data.remote.message

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.message.model.AddReactionRequest
import com.connectx.app.data.remote.message.model.EditMessageRequest
import com.connectx.app.data.remote.message.model.MessageDto
import com.connectx.app.data.remote.message.model.PagedMessageResponseDto
import com.connectx.app.data.remote.message.model.SendMessageRequestDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Full Android-side contract for the backend's Message API, verified directly
 * against com.connectx.message.controller.MessageController -- **11 endpoints**,
 * not the 10 reported by N2.0. A real discrepancy, corrected here the same way
 * N2.2 corrected the User count -- confirmed via a literal grep for
 * @(Get|Post|Put|Patch|Delete)Mapping in the controller source. Recorded in
 * docs/CONNECTX_ANDROID_DEVELOPMENT.md Section 32.
 *
 * This is the REST contract only. WebSocket/STOMP realtime delivery, typing
 * indicators, and realtime read receipts are entirely separate (N6) -- the
 * WS equivalents of send/delivered/read (/app/message.send etc.) are NOT part of
 * this interface. E2EE (N7) is not implemented -- ciphertext/nonce fields are
 * opaque strings only. Media upload/download (N2.6/MediaApi) is not implemented --
 * mediaId/mimeType/fileSizeBytes/mediaNonce are modeled only because they are
 * scalar fields genuinely returned on MessageDto itself.
 *
 * Contract only -- no call site exists anywhere yet. All 11 endpoints require
 * authentication on the backend; no Authorization header handling is added here
 * (deferred to N3).
 */
interface MessageApi {

    @GET("api/v1/conversations/{conversationId}/messages")
    suspend fun getConversationMessages(
        @Path("conversationId") conversationId: Long,
        @Query("before") before: Long? = null,
        @Query("limit") limit: Int? = null
    ): ApiResponse<PagedMessageResponseDto>

    @POST("api/v1/messages")
    suspend fun sendMessage(@Body request: SendMessageRequestDto): ApiResponse<MessageDto>

    @DELETE("api/v1/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("messageId") messageId: Long,
        @Query("deleteForEveryone") deleteForEveryone: Boolean? = null
    ): ApiResponse<String>

    @POST("api/v1/messages/{messageId}/reactions")
    suspend fun addOrUpdateReaction(
        @Path("messageId") messageId: Long,
        @Body request: AddReactionRequest
    ): ApiResponse<MessageDto>

    @DELETE("api/v1/messages/{messageId}/reactions")
    suspend fun removeReaction(@Path("messageId") messageId: Long): ApiResponse<MessageDto>

    @PUT("api/v1/messages/{messageId}")
    suspend fun editMessage(
        @Path("messageId") messageId: Long,
        @Body request: EditMessageRequest
    ): ApiResponse<MessageDto>

    @POST("api/v1/messages/{messageId}/pin")
    suspend fun pinMessage(@Path("messageId") messageId: Long): ApiResponse<MessageDto>

    @DELETE("api/v1/messages/{messageId}/pin")
    suspend fun unpinMessage(@Path("messageId") messageId: Long): ApiResponse<MessageDto>

    @GET("api/v1/conversations/{conversationId}/pinned-message")
    suspend fun getPinnedMessage(@Path("conversationId") conversationId: Long): ApiResponse<MessageDto>

    @POST("api/v1/messages/{messageId}/star")
    suspend fun starMessage(@Path("messageId") messageId: Long): ApiResponse<String>

    @DELETE("api/v1/messages/{messageId}/star")
    suspend fun unstarMessage(@Path("messageId") messageId: Long): ApiResponse<String>
}
