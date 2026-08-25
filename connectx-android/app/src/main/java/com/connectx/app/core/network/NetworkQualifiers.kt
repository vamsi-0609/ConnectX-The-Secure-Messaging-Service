package com.connectx.app.core.network

import javax.inject.Qualifier

/**
 * Marks the `OkHttpClient`/`Retrofit` pair that carries [com.connectx.app.core.network.auth.AuthInterceptor]
 * and [com.connectx.app.core.network.auth.TokenAuthenticator] -- every API interface
 * except [com.connectx.app.data.remote.auth.AuthApi] is built from this one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

/**
 * Marks the plain `OkHttpClient`/`Retrofit` pair with no interceptor and no
 * authenticator, used exclusively by [com.connectx.app.data.remote.auth.AuthApi]
 * (register/login/refresh -- all `permitAll()` on the backend). Kept
 * structurally separate from [AuthenticatedClient] so that: (1) a bad-credentials
 * 401 from `login` can never reach `TokenAuthenticator`, and (2) the refresh
 * call `TokenAuthenticator` itself makes can never recursively trigger the same
 * authenticator. See `NetworkModule`/docs Section 38 for the full rationale.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedClient
