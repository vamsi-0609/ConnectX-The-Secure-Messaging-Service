package com.connectx.app.core.network.auth

import com.connectx.app.data.local.auth.TokenStorage
import com.connectx.app.data.remote.auth.AuthRepository
import com.connectx.app.data.remote.auth.AuthResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive 401 -> refresh -> retry handler for the authenticated `OkHttpClient`
 * only (see `NetworkModule`) -- `AuthApi` (register/login/refresh) lives on a
 * separate, unauthenticated client, so this class is structurally never
 * invoked for a bad-credentials 401 from `login`, and the refresh call this
 * class itself makes (via [authRepository]) can never recursively re-enter
 * `authenticate()`. OkHttp only ever calls [authenticate] for a genuine 401
 * response -- a 403/404/409/422/500/etc. never reaches this class at all, by
 * OkHttp's own contract for `Authenticator`.
 *
 * # Single-flight refresh
 * Concurrent 401s (e.g. 5 requests that all had the same now-expired access
 * token) must produce exactly one `/auth/refresh` call, not five. A
 * [Mutex] serializes [authenticate] across every OkHttp dispatcher thread that
 * reaches it concurrently ([runBlocking] bridges the synchronous
 * `Authenticator` contract to the suspending [AuthRepository.refresh] call --
 * safe here because OkHttp already runs `authenticate` on a background
 * dispatcher thread, never the main thread). Each thread, once it acquires the
 * lock, first re-reads the CURRENT stored access token and compares it to the
 * token that was actually on ITS failed request: if they already differ,
 * another thread refreshed first while this one waited, so this thread reuses
 * that new token directly instead of calling refresh again. Only the first
 * thread to reach the lock with a still-stale token performs the real network
 * call.
 *
 * # Retry limit
 * [responseCount] walks `priorResponse` to detect a request that has already
 * been retried once; a second consecutive 401 for the same logical request
 * gives up (`null`) rather than looping.
 *
 * # Credential rejection vs. network failure
 * A refresh call that fails because the backend rejected the refresh token
 * ([AuthResult.ApiError]/[AuthResult.InvalidResponse]/[AuthResult.UnexpectedError])
 * means the credentials themselves are no good -- [AuthRepository.logout] is
 * called to clear the local session, matching N3.0's requirement that an
 * expired/invalid refresh token must end the session, not leave it
 * `Authenticated` with unusable credentials. A refresh call that fails purely
 * because of connectivity ([AuthResult.NetworkError]) does NOT clear anything
 * -- the stored tokens may well still be perfectly valid; this attempt simply
 * gives up so the original request's failure propagates normally, and a later
 * request gets to try again once the network recovers.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val authRepository: AuthRepository
) : Authenticator {

    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) {
            // Already retried once for this request chain -- refreshing again
            // and retrying forever is exactly the loop this must prevent.
            return null
        }

        val failedAccessToken = bearerToken(response.request)

        val newAccessToken = runBlocking {
            refreshMutex.withLock {
                val alreadyRefreshed = tokenStorage.getAccessToken()
                if (!alreadyRefreshed.isNullOrBlank() && alreadyRefreshed != failedAccessToken) {
                    return@withLock alreadyRefreshed
                }

                val refreshToken = tokenStorage.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    // No refresh token to use -- there is nothing left to try.
                    authRepository.logout()
                    return@withLock null
                }

                when (val result = authRepository.refresh(refreshToken)) {
                    is AuthResult.Success -> tokenStorage.getAccessToken()
                    is AuthResult.NetworkError -> null
                    is AuthResult.ApiError,
                    is AuthResult.InvalidResponse,
                    is AuthResult.UnexpectedError -> {
                        authRepository.logout()
                        null
                    }
                }
            }
        }

        val accessToken = newAccessToken ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()
    }

    private fun bearerToken(request: Request): String? =
        request.header("Authorization")?.removePrefix("Bearer ")

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
