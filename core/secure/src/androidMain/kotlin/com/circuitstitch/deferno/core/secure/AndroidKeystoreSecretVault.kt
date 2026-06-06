package com.circuitstitch.deferno.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.circuitstitch.deferno.core.model.AccountId
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecretVault]: each bearer token is AES-256-GCM encrypted under a device-bound,
 * non-exportable key held in the AndroidKeyStore — deliberately the Keystore + AES-GCM
 * directly, not Jetpack Security Crypto (ADR-0009). The key never leaves the Keystore; only
 * the ciphertext (a fresh random GCM IV ‖ the GCM output) is persisted, in a dedicated
 * [SharedPreferences] file keyed by [AccountId]. A new IV is generated per encryption, as
 * GCM requires.
 *
 * The prefs file is excluded from OS backup / device transfer (see `res/xml/backup_rules.xml`
 * and `data_extraction_rules.xml`, ADR-0009) — a restored device re-authenticates rather than
 * carrying over ciphertext it can no longer decrypt.
 *
 * Construct one per process (it is an AppScope singleton, ADR-0008) with the application
 * [Context]; one Keystore key wraps every Account's token (Account isolation is by storage
 * key, and the key is non-exportable, so tokens never cross the boundary).
 */
class AndroidKeystoreSecretVault(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) : SecretVault {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * The AES-GCM key for [keyAlias], resolved from (or generated once into) the
     * AndroidKeyStore on first use. `lazy` is thread-safe, so concurrent first use of this
     * AppScope singleton cannot race two `generateKey` calls into clobbering the key.
     */
    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    }

    override fun putBearerToken(account: AccountId, token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
            val iv = cipher.iv
            val body = cipher.doFinal(token.encodeToByteArray())
            val payload = ByteArray(1 + iv.size + body.size).also {
                it[0] = iv.size.toByte()
                iv.copyInto(it, destinationOffset = 1)
                body.copyInto(it, destinationOffset = 1 + iv.size)
            }
            prefs.edit().putString(account.value, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Failed to store bearer token", e)
        } catch (e: IOException) {
            throw SecureStorageException("Failed to store bearer token", e)
        }
    }

    override fun getBearerToken(account: AccountId): String? {
        val encoded = prefs.getString(account.value, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = payload[0].toInt()
            val iv = payload.copyOfRange(1, 1 + ivSize)
            val body = payload.copyOfRange(1 + ivSize, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(body).decodeToString()
        } catch (e: IllegalArgumentException) {
            // A corrupt entry (bad Base64 or framing). The store is a cache (ADR-0009), so a
            // damaged entry degrades to a clean miss and the caller re-authenticates rather
            // than crashing on an unexpected runtime exception.
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Failed to read bearer token", e)
        } catch (e: IOException) {
            throw SecureStorageException("Failed to read bearer token", e)
        }
    }

    override fun deleteBearerToken(account: AccountId) {
        prefs.edit().remove(account.value).apply()
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val DEFAULT_KEY_ALIAS = "com.circuitstitch.deferno.secure.bearer"

        // Keep in sync with the backup-rule excludes in app/androidApp/src/main/res/xml.
        const val DEFAULT_PREFS_NAME = "deferno_secure_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}
