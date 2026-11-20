package com.wa2port.android.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class StoredOAuthCredentials(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val accountEmail: String = "",
    val accountName: String = "",
) {
    val isSignedIn: Boolean get() = refreshToken.isNotBlank() || accessToken.isNotBlank()
}

/**
 * Storage boundary for OAuth client credentials and tokens.
 * Production uses [AndroidKeystoreSyncSecretStore]; JVM unit tests use [InMemorySyncSecretStore].
 */
interface SyncSecretStore {
    fun load(): StoredOAuthCredentials
    fun save(credentials: StoredOAuthCredentials)

    /** Clears OAuth tokens and user identity while preserving configured OAuth client ID/secret. */
    fun clearTokens() {
        val current = load()
        save(
            StoredOAuthCredentials(
                clientId = current.clientId,
                clientSecret = current.clientSecret,
            ),
        )
    }
}

class InMemorySyncSecretStore(
    initial: StoredOAuthCredentials = StoredOAuthCredentials(),
) : SyncSecretStore {
    @Volatile
    private var state: StoredOAuthCredentials = initial

    @Synchronized
    override fun load(): StoredOAuthCredentials = state

    @Synchronized
    override fun save(credentials: StoredOAuthCredentials) {
        state = credentials
    }
}

/**
 * Encrypts [StoredOAuthCredentials] at rest using an Android Keystore AES-256/GCM/NoPadding key
 * and stores the IV + ciphertext in private SharedPreferences.
 */
class AndroidKeystoreSyncSecretStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) : SyncSecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): StoredOAuthCredentials {
        val encoded = prefs.getString(KEY_PAYLOAD, null) ?: return StoredOAuthCredentials()
        return runCatching {
            val raw = Base64.getDecoder().decode(encoded)
            if (raw.size <= GCM_IV_BYTES) return StoredOAuthCredentials()
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val cipherText = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(cipherText).toString(Charsets.UTF_8)
            syncJson.decodeFromString<StoredOAuthCredentials>(plain)
        }.getOrElse {
            StoredOAuthCredentials()
        }
    }

    @Synchronized
    override fun save(credentials: StoredOAuthCredentials) {
        val plain = syncJson.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        val encoded = Base64.getEncoder().encodeToString(combined)
        prefs.edit().putString(KEY_PAYLOAD, encoded).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "wa2port_gdrive_sync_aes_gcm_v1"
        private const val DEFAULT_PREFS_NAME = "wa2port_gdrive_sync_secrets"
        private const val KEY_PAYLOAD = "oauth_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
