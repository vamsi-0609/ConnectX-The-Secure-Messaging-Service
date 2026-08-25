package com.connectx.app.core.network.auth

import com.connectx.app.data.local.auth.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches `Authorization: Bearer <accessToken>` to every request on the
 * authenticated `OkHttpClient` when a token is currently stored -- nothing
 * more. No refresh, no retry, no network call of its own, no logging (the
 * request/response bodies and headers are never written to any log from
 * here).
 *
 * Only attached to the AUTHENTICATED client (see `NetworkModule`) -- `AuthApi`
 * (register/login/refresh) lives on a separate, unauthenticated client
 * entirely, so this interceptor never runs for those three calls. That means
 * no path-based exclusion list is needed here: every request this interceptor
 * ever sees is already meant to carry a token if one exists.
 *
 * `TokenStorage` reads are synchronous `SharedPreferences` calls -- safe to
 * call directly from `intercept`, which OkHttp always invokes on one of its
 * own dispatcher threads, never the main thread.
 */
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val accessToken = tokenStorage.getAccessToken()

        val request = if (accessToken.isNullOrBlank()) {
            original
        } else {
            original.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        }

        return chain.proceed(request)
    }
}
