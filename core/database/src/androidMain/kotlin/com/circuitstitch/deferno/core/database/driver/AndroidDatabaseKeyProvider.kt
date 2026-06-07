package com.circuitstitch.deferno.core.database.driver

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.circuitstitch.deferno.core.database.DatabaseKeyException
import com.circuitstitch.deferno.core.database.DatabaseKeyStore
import com.circuitstitch.deferno.core.model.AccountId
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [DatabaseKeyStore]: the per-Account SQLCipher passphrase, minted once per Account and held
 * AES-256-GCM-encrypted under a device-bound, non-exportable AndroidKeyStore key — the same crypto
 * shape as `AndroidKeystoreSecretVault`, deliberately a **separate** store (its own key alias + prefs
 * file) rather than folded into the bearer vault (ADR-0009, ADR-0014). On first use for an Account a
 * high-entropy random passphrase is generated and persisted; thereafter the stored one is returned,
 * so the encrypted DB always opens with the same key.
 *
 * The passphrase store is excluded from OS backup (see `res/xml/backup_rules.xml` +
 * `data_extraction_rules.xml`) — like the per-Account DB it protects, it is a device-bound cache: a
 * restored device re-syncs rather than carrying a key it can no longer use. Never logs the key
 * (ADR-0009).
 *
 * Construct one per process (an AppScope singleton, ADR-0014) with the application [Context]. Runs
 * only on a device, so it is exercised by instrumented tests, not the headless JVM coverage gate
 * (excluded with the `driver` package in `CoverageConfig`).
 */
class AndroidDatabaseKeyProvider(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) : DatabaseKeyStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // Serializes the get-or-mint read-modify-write so two concurrent first uses of one Account can't
    // race two passphrases into existence (the loser would leave a DB no key can open).
    private val lock = Any()

    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateWrappingKey()
    }

    override fun databaseKey(account: AccountId): String = synchronized(lock) {
        read(account) ?: mint().also { write(account, it) }
    }

    override fun deleteKey(account: AccountId): Unit = synchronized(lock) {
        prefs.edit().remove(account.value).apply()
    }

    private fun read(account: AccountId): String? {
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
            // A corrupt entry (bad Base64 / framing) degrades to a miss: the caller mints a fresh
            // key, and a stale per-Account DB it can no longer open is dropped + re-synced (the same
            // cache posture as the token vault, ADR-0009).
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyException("Failed to read database key", e)
        } catch (e: IOException) {
            throw DatabaseKeyException("Failed to read database key", e)
        }
    }

    private fun write(account: AccountId, key: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
            val iv = cipher.iv
            val body = cipher.doFinal(key.encodeToByteArray())
            val payload = ByteArray(1 + iv.size + body.size).also {
                it[0] = iv.size.toByte()
                iv.copyInto(it, destinationOffset = 1)
                body.copyInto(it, destinationOffset = 1 + iv.size)
            }
            prefs.edit().putString(account.value, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyException("Failed to store database key", e)
        } catch (e: IOException) {
            throw DatabaseKeyException("Failed to store database key", e)
        }
    }

    /** A fresh 256-bit random passphrase, Base64-encoded to a lossless String (SQLCipher takes UTF-8). */
    private fun mint(): String {
        val raw = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun generateWrappingKey(): SecretKey =
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
        const val DEFAULT_KEY_ALIAS = "com.circuitstitch.deferno.database.dbkey"

        // Keep in sync with the backup-rule excludes in app/androidApp/src/main/res/xml.
        const val DEFAULT_PREFS_NAME = "deferno_db_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val PASSPHRASE_BYTES = 32
    }
}
