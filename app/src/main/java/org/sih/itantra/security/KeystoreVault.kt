package org.sih.itantra.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local at-rest encryption backed by the Android Keystore. The AES-256 key is generated inside the
 * Keystore (hardware-backed / StrongBox where the device has it) and its raw bytes never leave it.
 * Output format: `[12B IV][ciphertext + 16B GCM tag]`.
 */
object KeystoreVault {
    private const val TAG = "KeystoreVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "itantra_local_storage_v1"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Keystore generates a fresh random IV
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed: ${e.message}")
            null
        }
    }

    fun encryptString(s: String): ByteArray = encrypt(s.toByteArray(Charsets.UTF_8))
    fun decryptString(data: ByteArray): String? = decrypt(data)?.toString(Charsets.UTF_8)
}
