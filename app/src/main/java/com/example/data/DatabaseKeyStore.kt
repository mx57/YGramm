package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SQLCipher passphrase is random per install and wrapped by a hardware-backed
 * Android Keystore AES-GCM key, so the key material never leaves the TEE/StrongBox
 * and is not stored in plaintext anywhere.
 */
internal object DatabaseKeyStore {
    private const val TAG = "YggGramDbKey"
    private const val PREFS_NAME = "ygggram_secure_store"
    private const val PREF_WRAPPED_PASSPHRASE = "db_passphrase_wrapped_v1"
    private const val KEYSTORE_ALIAS = "ygggram_db_passphrase_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(PREF_WRAPPED_PASSPHRASE, null)
        if (!wrapped.isNullOrBlank()) {
            try {
                return unwrap(wrapped)
            } catch (e: Exception) {
                // Without the Keystore key the existing ciphertext cannot be recovered.
                // Keep it aside for diagnostics instead of silently deleting user data.
                Log.e(
                    TAG,
                    "DB passphrase cannot be unwrapped (${e.javaClass.simpleName}); " +
                        "the unreadable store will be preserved",
                    e
                )
                quarantineUnreadableStore(context)
            }
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(PREF_WRAPPED_PASSPHRASE, wrap(passphrase))
            .commit()
        return passphrase
    }

    private fun wrap(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(passphrase), Base64.NO_WRAP)
    }

    private fun unwrap(wrapped: String): ByteArray {
        val blob = Base64.decode(wrapped, Base64.NO_WRAP)
        require(blob.size > GCM_IV_BYTES + 16) { "Wrapped passphrase is truncated" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKeystoreKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES)
        )
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun quarantineUnreadableStore(context: Context) {
        val dbFile = context.getDatabasePath(YggDatabase.DATABASE_NAME)
        if (!dbFile.exists()) return
        val stamp = System.currentTimeMillis()
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = File(dbFile.path + suffix)
            if (!file.exists()) return@forEach
            val target = File(file.path + ".unreadable-$stamp")
            if (!file.renameTo(target)) {
                Log.e(TAG, "Cannot preserve unreadable store ${file.name}")
            }
        }
    }
}
