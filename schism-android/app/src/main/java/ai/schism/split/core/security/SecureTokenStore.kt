package ai.schism.split.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.os.Build
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one secret encrypted by a non-exportable Android Keystore key. Defaults to the backend
 * bearer token; [fileName]/[entryKey] let a second secret (the user's own receipt-AI API key) reuse
 * the exact same AES-GCM format and Keystore key without sharing a prefs file with it.
 */
class SecureTokenStore(
    context: Context,
    fileName: String = FILE_NAME,
    private val entryKey: String = KEY_CIPHERTEXT,
    // Last so it stays usable as a trailing lambda.
    private val keyProvider: () -> SecretKey = ::getOrCreateKey,
) {
    private val preferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(): String = synchronized(lock) {
        val encoded = preferences.getString(entryKey, null) ?: return@synchronized ""
        runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
            }
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(entryKey).commit()
            ""
        }
    }

    fun write(token: String) = synchronized(lock) {
        if (token.isBlank()) {
            clear()
            return@synchronized
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider())
        }
        val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(entryKey, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to persist encrypted auth token" }
    }

    fun clear() {
        check(preferences.edit().remove(entryKey).commit()) {
            "Unable to clear encrypted auth token"
        }
    }

    internal fun encryptedValueForTest(): String? = preferences.getString(entryKey, null)

    private companion object {
        const val FILE_NAME = "secure_auth"
        const val KEY_CIPHERTEXT = "token_v1"
        const val KEY_ALIAS = "schism.auth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        fun getOrCreateKey(): SecretKey {
            // Robolectric has no AndroidKeyStore provider. This branch is unreachable in an Android
            // package and lets JVM tests exercise the exact AES-GCM storage format and migration.
            if (Build.FINGERPRINT == "robolectric") return RobolectricKey.key
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

        object RobolectricKey {
            val key: SecretKey by lazy {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES).apply { init(256) }.generateKey()
            }
        }
    }
}
