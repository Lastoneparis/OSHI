package com.oshi.messenger.network.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.encryption.CryptoManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import java.io.IOException

/**
 * __CALL_PUSH_AUTH_2026_09_23__ The Android half of [CallPushAuth]: where the key comes
 * from, and the one interceptor every call/push OkHttp client installs.
 *
 * The key is the app-wide [CryptoManager]'s Ed25519 pair — the SAME one V2Signer signs
 * `/v2/` with and V2KeysClient publishes as `signingKey`, which is what the server's
 * TOFU binding table holds for this identity.
 *
 * Why a static entry point and not constructor injection: two of the ten call sites are
 * `FCMService` companion functions and `onNewToken` in a service that can start in a
 * process where no Activity ever ran. For the same reason [ensureSigningKey] can load the
 * pair from `oshi_secure_keys` itself: `MainViewModel.restoreKeys` is otherwise the only
 * thing that puts it in memory, and an FCM-woken process never builds a MainViewModel.
 */
object CallPushSigning {
    private const val TAG = "CallPushSigning"
    private const val PREFS_NAME = "oshi_secure_keys"            // MainViewModel.PREFS_NAME
    private const val KEY_SIGNING_PRIVATE = "signing_private_key" // MainViewModel.KEY_SIGNING_PRIVATE_KEY
    private const val KEY_SIGNING_PUBLIC = "signing_public_key"   // MainViewModel.KEY_SIGNING_PUBLIC_KEY

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallPushSigningEntryPoint {
        fun cryptoManager(): CryptoManager
    }

    @Volatile private var appContext: Context? = null

    /** Called first thing in `OSHIApplication.onCreate`. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val interceptor: Interceptor = CallPushAuthInterceptor(
        CallPushAuth.userAgent(BuildConfig.VERSION_NAME)
    ) { method, path, body -> headersFor(method, path, body) }

    private fun cryptoManager(): CryptoManager {
        val ctx = appContext ?: throw IOException("CallPushSigning not initialised")
        return EntryPointAccessors.fromApplication(ctx, CallPushSigningEntryPoint::class.java).cryptoManager()
    }

    fun headersFor(method: String, path: String, body: ByteArray): Map<String, String> {
        val cm = cryptoManager()
        ensureSigningKey(cm)
        return CallPushAuth.headers(
            method = method,
            path = path,
            body = body,
            timestampMs = System.currentTimeMillis(),
            signingPublicKey = cm.getSigningPublicKey(),
            sign = { cm.sign(it) },
        )
    }

    @Synchronized
    private fun ensureSigningKey(cm: CryptoManager) {
        if (runCatching { cm.getSigningPublicKey() }.isSuccess) return
        val ctx = appContext ?: throw IOException("no signing key")
        val prefs = try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            throw IOException("secure key store unavailable: ${e.message}", e)
        }
        val priv = prefs.getString(KEY_SIGNING_PRIVATE, null)
        val pub = prefs.getString(KEY_SIGNING_PUBLIC, null)
        if (priv == null || pub == null) throw IOException("no signing key (no identity yet)")
        val privBytes = Base64.decode(priv, Base64.NO_WRAP)
        val pubBytes = Base64.decode(pub, Base64.NO_WRAP)
        cm.setSigningKeyPair(privBytes, pubBytes)
        Log.d(TAG, "signing key loaded from secure storage for a background request")
    }
}
