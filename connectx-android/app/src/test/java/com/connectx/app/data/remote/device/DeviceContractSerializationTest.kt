package com.connectx.app.data.remote.device

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.device.model.DeviceResponseDto
import com.connectx.app.data.remote.device.model.RegisterDeviceDto
import com.connectx.app.data.remote.device.model.UserPublicKeyDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-time/JVM-only verification that the N2.7 Device DTOs match the actual
 * backend JSON contract, read directly from connectx-backend source
 * (device/dto/RegisterDeviceDto.java, DeviceResponseDto.java, UserPublicKeyDto.java,
 * device/controller/DeviceController.java). No network call, no Retrofit, no
 * OkHttp client.
 */
class DeviceContractSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `RegisterDeviceDto encodes with exact backend field names`() {
        val request = RegisterDeviceDto(
            deviceName = "Pixel 7a",
            publicKey = "opaque-ecdh-public-key",
            keyAlgorithm = "ECDH-P256"
        )
        assertEquals(
            """{"deviceName":"Pixel 7a","publicKey":"opaque-ecdh-public-key","keyAlgorithm":"ECDH-P256"}""",
            json.encodeToString(request)
        )
    }

    @Test
    fun `ApiResponse of DeviceResponseDto decodes a realistic registerDevice response`() {
        // Shaped like DeviceController#registerDevice -> ApiResponse.success(..., DeviceResponseDto)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Device registered successfully",
              "data": {
                "id": 42,
                "userId": 7,
                "deviceName": "Pixel 7a",
                "publicKey": "opaque-ecdh-public-key",
                "keyAlgorithm": "ECDH-P256",
                "createdAt": "2026-08-25T09:00:00Z",
                "lastSeenAt": "2026-08-25T09:00:00Z",
                "active": true
              },
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<DeviceResponseDto> = json.decodeFromString(rawJson)

        assertTrue(response.success)
        val device = response.data!!
        assertEquals(42L, device.id)
        assertEquals(7L, device.userId)
        assertEquals("Pixel 7a", device.deviceName)
        assertTrue(device.active)
    }

    @Test
    fun `ApiResponse of List DeviceResponseDto decodes getMyDevices with an inactive device`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User devices",
              "data": [
                {
                  "id": 42,
                  "userId": 7,
                  "deviceName": "Pixel 7a",
                  "publicKey": "opaque-key-1",
                  "keyAlgorithm": "ECDH-P256",
                  "createdAt": "2026-08-25T09:00:00Z",
                  "lastSeenAt": "2026-08-25T09:00:00Z",
                  "active": true
                },
                {
                  "id": 43,
                  "userId": 7,
                  "deviceName": "Old Phone",
                  "publicKey": "opaque-key-2",
                  "keyAlgorithm": "RSA-OAEP",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "lastSeenAt": null,
                  "active": false
                }
              ],
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<DeviceResponseDto>> = json.decodeFromString(rawJson)

        val devices = response.data!!
        assertEquals(2, devices.size)
        assertFalse(devices[1].active)
        assertEquals(null, devices[1].lastSeenAt)
    }

    @Test
    fun `ApiResponse of String decodes deactivateDevice response`() {
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "Device deactivated successfully",
              "data": "Deactivated",
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<String> = json.decodeFromString(rawJson)
        assertEquals("Deactivated", response.data)
    }

    @Test
    fun `ApiResponse of List UserPublicKeyDto decodes getUserPublicKeys with multiple device entries`() {
        // Shaped like DeviceController#getUserPublicKeys -> ApiResponse.success(..., List<UserPublicKeyDto>)
        val rawJson = """
            {
              "success": true,
              "code": "SUCCESS",
              "message": "User public key endpoints",
              "data": [
                {
                  "deviceId": 42,
                  "userId": 7,
                  "deviceName": "Pixel 7a",
                  "publicKey": "opaque-key-1",
                  "keyAlgorithm": "ECDH-P256"
                },
                {
                  "deviceId": 55,
                  "userId": 7,
                  "deviceName": "Vivo X200 FE",
                  "publicKey": "opaque-key-2",
                  "keyAlgorithm": "ECDH-P256"
                }
              ],
              "timestamp": "2026-08-25T09:00:00Z"
            }
        """.trimIndent()

        val response: ApiResponse<List<UserPublicKeyDto>> = json.decodeFromString(rawJson)

        val keys = response.data!!
        assertEquals(2, keys.size)
        assertEquals(7L, keys[0].userId)
        assertEquals("Vivo X200 FE", keys[1].deviceName)
    }
}
