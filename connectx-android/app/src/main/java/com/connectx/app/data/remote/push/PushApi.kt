package com.connectx.app.data.remote.push

import com.connectx.app.core.network.model.ApiResponse
import com.connectx.app.data.remote.push.model.PushSubscriptionRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Full Android-side contract for the backend's Push API, verified directly
 * against com.connectx.push.controller.PushNotificationController -- 3 endpoints,
 * matching the N2.0 inspection's reported count exactly (no discrepancy this time).
 *
 * getVapidPublicKey's response data is a raw `Map<String, String>` on the backend
 * (`Map.of("vapidPublicKey", publicKey)`), not a dedicated DTO -- modeled as
 * Map<String, String> directly rather than inventing a single-field wrapper class.
 *
 * subscribe/unsubscribe both return `ApiResponse<Void>` on the backend (data is
 * always null) -- modeled as ApiResponse<Unit>, the closest Kotlin equivalent;
 * `data` stays nullable on ApiResponse regardless of T, so decoding a null "data"
 * field works correctly.
 *
 * This is Web Push (VAPID/p256dh), the browser Push API model already used by the
 * PWA frontend -- NOT Firebase Cloud Messaging. Contract only -- no call site
 * exists anywhere yet, no Firebase SDK, no notification handling, no permission
 * requests. Both mutating endpoints require authentication on the backend; no
 * Authorization header handling is added here (deferred to N3).
 */
interface PushApi {

    @GET("api/v1/push/vapid-public-key")
    suspend fun getVapidPublicKey(): ApiResponse<Map<String, String>>

    @POST("api/v1/push/subscribe")
    suspend fun subscribe(@Body request: PushSubscriptionRequestDto): ApiResponse<Unit>

    @POST("api/v1/push/unsubscribe")
    suspend fun unsubscribe(@Query("endpoint") endpoint: String? = null): ApiResponse<Unit>
}
