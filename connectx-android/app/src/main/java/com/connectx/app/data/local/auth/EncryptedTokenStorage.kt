package com.connectx.app.data.local.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TokenStorage] backed by Jetpack Security's `EncryptedSharedPreferences` --
 * AES-256-GCM value encryption + AES-256-SIV key encryption, with the encryption
 * key itself held in the Android Keystore (hardware-backed on most devices).
 * This is the standard, currently-recommended mechanism for small secret
 * strings like these two tokens; nothing here is a custom Keystore integration.
 *
 * The underlying `SharedPreferences` instance is never exposed outside this
 * class -- every other layer talks to [TokenStorage]'s three-string-in,
 * three-string-out surface only.
 *
 * Thread safety: `SharedPreferences` (and `EncryptedSharedPreferences`, which
 * wraps a real `SharedPreferences` file) already guarantees safe concurrent
 * reads/writes internally -- no additional synchronization is added here. Both
 * tokens are written through a single `Editor` and a single `commit()` call, so
 * a reader never observes one token updated and the other still stale.
 * `commit()` (not `apply()`) is used deliberately: it writes synchronously and
 * returns success/failure, so a save can never race a subsequent read on
 * another thread the way `apply()`'s asynchronous disk write could.
 */
@Singleton
class EncryptedTokenStorage @Inject constructor(
    @ApplicationContext context: Context
) : TokenStorage {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commit()
    }

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    override fun getTokens(): TokenPair? {
        val accessToken = getAccessToken() ?: return null
        val refreshToken = getRefreshToken() ?: return null
        return TokenPair(accessToken, refreshToken)
    }

    override fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
    }

    override fun hasTokens(): Boolean = getTokens() != null

    private companion object {
        const val PREFS_FILE_NAME = "connectx_secure_auth_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
