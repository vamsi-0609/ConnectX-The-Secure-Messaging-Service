package com.connectx.app.data.remote.media

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.media.model.MediaUploadResponseDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Full Android-side contract for the backend's Media API, verified directly
 * against com.connectx.media.controller.MediaController -- **2 endpoints**,
 * matching the N2.0 inspection's reported count exactly (no discrepancy this
 * time; only N2.2's User and N2.5's Message groups were undercounted).
 *
 * `nonce`/`groupKeyVersion`/`mimeType` are optional multipart form fields (the
 * backend reads them via @RequestParam under a multipart/form-data content type,
 * not query parameters -- confirmed from the controller's
 * `consumes = MULTIPART_FORM_DATA_VALUE` upload endpoint), so they are modeled
 * as optional @Part RequestBody parts, not @Query.
 *
 * Download returns raw Resource on the backend (never ApiResponse-wrapped) --
 * modeled as ResponseBody + @Streaming, the exact same pattern already
 * established for UserApi.getProfileImage in N2.2. Content-Type/Content-Disposition
 * are decided server-side based on whether the media is encrypted (nonce
 * presence) -- the client reads them from the response headers, no logic to
 * replicate here.
 *
 * Contract only -- no call site exists anywhere yet, no file picker, no upload/
 * download manager, no media UI. Both endpoints require authentication on the
 * backend; no Authorization header handling is added here (deferred to N3). No
 * cryptographic behavior is implemented -- nonce/groupKeyVersion are opaque
 * scalar fields only (N7's boundary).
 */
interface MediaApi {

    @Multipart
    @POST("api/v1/conversations/{conversationId}/media")
    suspend fun uploadConversationMedia(
        @Path("conversationId") conversationId: Long,
        @Part file: MultipartBody.Part,
        @Part("nonce") nonce: RequestBody? = null,
        @Part("groupKeyVersion") groupKeyVersion: RequestBody? = null,
        @Part("mimeType") mimeType: RequestBody? = null
    ): ApiResponse<MediaUploadResponseDto>

    @Streaming
    @GET("api/v1/media/{mediaId}")
    suspend fun getMedia(@Path("mediaId") mediaId: Long): ResponseBody
}
