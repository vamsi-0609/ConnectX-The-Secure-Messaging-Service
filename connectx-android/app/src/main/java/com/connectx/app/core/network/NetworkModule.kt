package com.connectx.app.core.network

import com.connectx.app.BuildConfig
import com.connectx.app.core.network.auth.AuthInterceptor
import com.connectx.app.core.network.auth.TokenAuthenticator
import com.connectx.app.data.remote.auth.AuthApi
import com.connectx.app.data.remote.block.BlockApi
import com.connectx.app.data.remote.connection.ConnectionApi
import com.connectx.app.data.remote.conversation.ConversationApi
import com.connectx.app.data.remote.device.DeviceApi
import com.connectx.app.data.remote.group.GroupApi
import com.connectx.app.data.remote.media.MediaApi
import com.connectx.app.data.remote.message.MessageApi
import com.connectx.app.data.remote.push.PushApi
import com.connectx.app.data.remote.user.UserApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * N3.3: two independent `OkHttpClient`/`Retrofit` pairs, not one --
 *
 * ```
 * [UnauthenticatedClient] OkHttpClient (no interceptor, no authenticator)
 *       -> Retrofit -> AuthApi (register/login/refresh)
 *
 * [AuthenticatedClient] OkHttpClient (AuthInterceptor + TokenAuthenticator)
 *       -> Retrofit -> every other API (User/Connection/Block/Conversation/
 *                       Message/Media/Device/Push/Group)
 * ```
 *
 * This is a deliberate split, not incidental duplication. A single shared
 * client would create a real dependency cycle: `OkHttpClient -> Authenticator
 * -> AuthRepository -> AuthApi -> Retrofit -> OkHttpClient`. Splitting `AuthApi`
 * onto its own client breaks that cycle (`TokenAuthenticator` depends on
 * `AuthRepository`, which depends on the UNAUTHENTICATED client -- a
 * completely separate object from the AUTHENTICATED client `TokenAuthenticator`
 * is itself attached to; there is no path back). It also solves two smaller
 * problems for free: a bad-credentials 401 from `login` can never reach
 * `TokenAuthenticator` (it isn't on that client), and the refresh call
 * `TokenAuthenticator` makes can never recursively trigger itself (same
 * reason). See docs Section 38 for the full writeup.
 *
 * Both clients still resolve `BuildConfig.BASE_URL` through the same
 * mechanism as before -- no hostname is hardcoded anywhere in this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    @Provides
    @Singleton
    @UnauthenticatedClient
    fun provideUnauthenticatedOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().build()
    }

    @Provides
    @Singleton
    @UnauthenticatedClient
    fun provideUnauthenticatedRetrofit(
        @UnauthenticatedClient okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(@UnauthenticatedClient retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .build()
    }

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedRetrofit(
        @AuthenticatedClient okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideUserApi(@AuthenticatedClient retrofit: Retrofit): UserApi {
        return retrofit.create(UserApi::class.java)
    }

    @Provides
    @Singleton
    fun provideConnectionApi(@AuthenticatedClient retrofit: Retrofit): ConnectionApi {
        return retrofit.create(ConnectionApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBlockApi(@AuthenticatedClient retrofit: Retrofit): BlockApi {
        return retrofit.create(BlockApi::class.java)
    }

    @Provides
    @Singleton
    fun provideConversationApi(@AuthenticatedClient retrofit: Retrofit): ConversationApi {
        return retrofit.create(ConversationApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMessageApi(@AuthenticatedClient retrofit: Retrofit): MessageApi {
        return retrofit.create(MessageApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMediaApi(@AuthenticatedClient retrofit: Retrofit): MediaApi {
        return retrofit.create(MediaApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDeviceApi(@AuthenticatedClient retrofit: Retrofit): DeviceApi {
        return retrofit.create(DeviceApi::class.java)
    }

    @Provides
    @Singleton
    fun providePushApi(@AuthenticatedClient retrofit: Retrofit): PushApi {
        return retrofit.create(PushApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGroupApi(@AuthenticatedClient retrofit: Retrofit): GroupApi {
        return retrofit.create(GroupApi::class.java)
    }
}
