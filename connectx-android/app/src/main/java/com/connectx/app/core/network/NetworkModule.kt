package com.connectx.app.core.network

import com.connectx.app.BuildConfig
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

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().build()
    }

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
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi {
        return retrofit.create(UserApi::class.java)
    }

    @Provides
    @Singleton
    fun provideConnectionApi(retrofit: Retrofit): ConnectionApi {
        return retrofit.create(ConnectionApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBlockApi(retrofit: Retrofit): BlockApi {
        return retrofit.create(BlockApi::class.java)
    }

    @Provides
    @Singleton
    fun provideConversationApi(retrofit: Retrofit): ConversationApi {
        return retrofit.create(ConversationApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMessageApi(retrofit: Retrofit): MessageApi {
        return retrofit.create(MessageApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMediaApi(retrofit: Retrofit): MediaApi {
        return retrofit.create(MediaApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi {
        return retrofit.create(DeviceApi::class.java)
    }

    @Provides
    @Singleton
    fun providePushApi(retrofit: Retrofit): PushApi {
        return retrofit.create(PushApi::class.java)
    }

    @Provides
    @Singleton
    fun provideGroupApi(retrofit: Retrofit): GroupApi {
        return retrofit.create(GroupApi::class.java)
    }
}
