package com.connectx.app.data.remote.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Minimal Hilt binding for [AuthRepository] -- kept separate from
 * `NetworkModule` (HTTP/Retrofit concerns only) and `AuthStorageModule`
 * (token-storage concerns only), matching the pattern already established in
 * N3.1. `AuthApi`/`SessionManager`/`Json` are already provided elsewhere
 * (`NetworkModule`, `SessionManager`'s own `@Inject constructor`); this module
 * adds nothing but the one binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}
