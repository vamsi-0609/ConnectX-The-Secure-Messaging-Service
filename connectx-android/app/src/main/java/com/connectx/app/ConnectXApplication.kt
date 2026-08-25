package com.connectx.app

import android.app.Application
import com.connectx.app.core.session.SessionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * N3.4: restores local session state once, at application startup.
 *
 * `SessionManager.restoreSession()` is a synchronous read (through
 * `TokenStorage`/`EncryptedSharedPreferences`, which itself does Keystore +
 * disk I/O in its constructor and on every read -- confirmed by direct
 * inspection of `EncryptedTokenStorage`) -- calling it directly from
 * `onCreate()` would block the main thread during startup. `applicationScope`
 * (`Dispatchers.IO`) exists for exactly this one call: no AndroidX Startup,
 * no second DI-provided scope, no other startup framework was introduced --
 * a single scope field, living exactly as long as this Application instance
 * (i.e. the process), is the simplest mechanism that satisfies "don't block
 * main thread" without adding a library or an unnecessary abstraction. If a
 * genuine second use for an app-scoped coroutine surfaces in a later phase,
 * it can be promoted to a Hilt-provided `@ApplicationScope CoroutineScope`
 * then -- premature to do so for a single call site today.
 *
 * `SessionManager` is field-injected by Hilt's generated `@HiltAndroidApp`
 * machinery before this class's `onCreate()` body runs (the generated base
 * class performs injection as part of `super.onCreate()`), so it is always
 * initialized here. It is the exact same `@Singleton` instance every other
 * layer (`AuthRepositoryImpl`, `TokenAuthenticator`) already depends on --
 * this call does not create a second session state, it only triggers the one
 * `SessionManager`'s existing `restoreSession()` method.
 *
 * Restoration itself remains exactly what N3.0/N3.1 designed: local-only,
 * optimistic ("tokens exist" -> `Authenticated`), no network request, no JWT
 * parsing/expiry check. That decision was re-verified during N3.4's
 * inspection and found still correct -- not changed here.
 */
@HiltAndroidApp
class ConnectXApplication : Application() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            sessionManager.restoreSession()
        }
    }
}
