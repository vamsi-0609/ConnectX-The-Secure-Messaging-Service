package com.connectx.app.data.local.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Minimal Hilt binding for [TokenStorage] -- kept separate from `NetworkModule`
 * (which provides HTTP/Retrofit concerns only) rather than added there for
 * convenience. Token storage is an application-level persistence boundary, not
 * a networking one; `@Singleton` matches that -- exactly one encrypted
 * preferences file/instance for the app's lifetime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthStorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: EncryptedTokenStorage): TokenStorage
}
