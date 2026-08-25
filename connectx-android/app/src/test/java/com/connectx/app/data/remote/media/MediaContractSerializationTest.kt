package com.connectx.app.data.remote.media

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.media.model.MediaUploadResponseDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.6 Media DTO matches the actual
 * backend JSON contract, read directly from connectx-backend source
 * (media/dto/MediaUploadResponseDto.java), plus a contract-only check of the raw
 * binary download representation (okhttp3.ResponseBody). No network call, no
 * Retrofit, no OkHttp client, no real file upload/download.
 */
class MediaContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `ApiResponse of MediaUploadResponseDto decodes a realistic DIRECT (unencrypted) upload response`() {
        // Shaped like MediaController#uploadConversationMedia -> ApiResponse.success(..., MediaUploadResponseDto)
        // DIRECT, unencrypted: nonce/groupKeyVersion both null.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Media uploaded successfully",
              "data": {
                "mediaId": 3001,
                "conversationId": 501,
                "mimeType": "image/png",
                "fileSizeBytes": 204800,
                "nonce": null,
                "groupKeyVersion": null
              },
              "timestamp": "2026-08-24T13:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<MediaUploadResponseDto> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val media = response.data!!
        assertEquals(3001L, media.mediaId)
        assertEquals("image/png", media.mimeType)
        assertEquals(204800L, media.fileSizeBytes)
        assertNull(media.nonce)
        assertNull(media.groupKeyVersion)
    }

    @Test
    fun `ApiResponse of MediaUploadResponseDto decodes a realistic GROUP E2EE upload response`() {
        // GROUP, encrypted: nonce + groupKeyVersion both present.
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Media uploaded successfully",
              "data": {
                "mediaId": 3002,
                "conversationId": 502,
                "mimeType": "application/pdf",
                "fileSizeBytes": 51200,
                "nonce": "opaque-media-nonce",
                "groupKeyVersion": 3
              },
              "timestamp": "2026-08-24T13:05:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<MediaUploadResponseDto> = json.decodeFromString(rawJson)

        val media = response.data!!
        assertEquals("opaque-media-nonce", media.nonce)
        assertEquals(3, media.groupKeyVersion)
    }

    @Test
    fun `raw binary download is represented as a plain ResponseBody, not wrapped in ApiResponse`() {
        // Contract-only check: getMedia() returns okhttp3.ResponseBody directly (matching
        // MediaController#getMedia's raw Resource return type -- never ApiResponse-wrapped).
        // No real network call is made; this just confirms the type can hold arbitrary bytes
        // and be read back, the same shape a real (unencrypted, inline) response would have.
        val fakeImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes
        val body = fakeImageBytes.toResponseBody("image/png".toMediaType())

        assertEquals("image/png", body.contentType().toString())
        assertEquals(fakeImageBytes.size.toLong(), body.contentLength())
        assertTrue(body.bytes().contentEquals(fakeImageBytes))
    }

    @Test
    fun `raw binary download for encrypted media is opaque octet-stream bytes`() {
        // Contract-only check for the encrypted-media branch (MediaController forces
        // application/octet-stream + attachment disposition when media.nonce is present).
        val ciphertextBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val body = ciphertextBytes.toResponseBody("application/octet-stream".toMediaType())

        assertEquals("application/octet-stream", body.contentType().toString())
        assertTrue(body.bytes().contentEquals(ciphertextBytes))
    }
}
