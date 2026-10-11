package app.truenasdock.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.truenasdock.api.TrueNasJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

enum class AuthMethod { API_KEY, PASSWORD }

@Serializable
data class ServerProfile(
    val url: String,
    val username: String,
    val authMethod: AuthMethod,
    /** SHA-256 of a self-signed certificate the user chose to trust. */
    val pinnedSha256: String? = null,
)

/**
 * Persists the server profile, with the API key/password encrypted by an Android Keystore AES-GCM
 * key that never leaves the device.
 */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun load(): Pair<ServerProfile, String>? {
        val profileJson = prefs.getString(KEY_PROFILE, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        val profile = runCatching { TrueNasJson.decodeFromString<ServerProfile>(profileJson) }.getOrNull() ?: return null
        return profile to secret
    }

    fun save(profile: ServerProfile, secret: String) = prefs.edit {
        putString(KEY_PROFILE, TrueNasJson.encodeToString(profile))
        putString(KEY_SECRET, encrypt(secret))
    }

    fun clear() = prefs.edit { clear() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        }
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "truenas_dock_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_PROFILE = "profile"
        const val KEY_SECRET = "secret"
    }
}
