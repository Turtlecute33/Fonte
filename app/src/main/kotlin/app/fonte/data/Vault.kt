package app.fonte.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the GitHub token with one AES-256-GCM key in AndroidKeyStore
 * (`fonte.vault.v1`, StrongBox when available, else TEE). No user-auth binding: the background job
 * needs the token while the screen is locked. Ciphertext = 12-byte IV + GCM output.
 */
class Vault {
    private val key: SecretKey by lazy(::loadOrCreateKey)

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) // the keystore picks a fresh random IV
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Throws [GeneralSecurityException] when [blob] is damaged or the key cannot decrypt it (e.g. a new key). */
    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("Ciphertext too short")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: ProviderException) {
            // Keystore failures surface as this unchecked exception.
            throw GeneralSecurityException(e)
        }
    }

    fun encryptString(plain: String): ByteArray = encrypt(plain.toByteArray(Charsets.UTF_8))
    fun decryptString(blob: ByteArray): String = decrypt(blob).toString(Charsets.UTF_8)

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return try {
            generateKey(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fonte.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
