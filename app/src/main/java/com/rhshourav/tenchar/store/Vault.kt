package com.rhshourav.tenchar.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The Keystore key is gone or can no longer be used (screen lock removed or changed). Data is unrecoverable by design. */
class KeysLostException(cause: Throwable? = null) : Exception("keys lost", cause)

/** Shows the system unlock prompt for [cipher] and calls back with the same cipher once the person is authenticated. */
fun interface Authenticator {
    fun authenticate(cipher: Cipher, onSuccess: (Cipher) -> Unit, onFailure: (cancelled: Boolean) -> Unit)
}

/**
 * Key hierarchy:
 *
 *   Android Keystore key (hardware-backed where possible, StrongBox if present)
 *     - AES-256-GCM, usable only after the screen-lock prompt, for every single use
 *     - destroyed by Android if the secure lock screen is removed
 *   wraps -> data key (32 random bytes, stored wrapped in tc_dek.bin)
 *   encrypts -> tc_state.bin (AES-256-GCM, the whole [Conversation])
 *
 * The data key lives in memory only while the app is unlocked. [lock] callers
 * must overwrite it. Nothing secret is ever written unencrypted.
 */
class Vault(context: Context) {
    private val dekFile = File(context.filesDir, "tc_dek.bin")
    private val stateFile = File(context.filesDir, "tc_state.bin")
    private val random = SecureRandom()

    fun exists(): Boolean = dekFile.exists()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Makes a new Keystore key and a cipher to wrap a new data key. Any old vault is discarded. */
    fun cipherForCreate(): Cipher {
        wipe()
        generateKey()
        val key = keyStore().getKey(ALIAS, null) as SecretKey
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** A cipher that will unwrap the stored data key once the person authenticates. */
    fun cipherForUnlock(): Cipher {
        val blob = try {
            dekFile.readBytes()
        } catch (e: IOException) {
            throw KeysLostException(e)
        }
        if (blob.size != IV_BYTES + DEK_BYTES + TAG_BYTES) throw KeysLostException()
        val key = try {
            keyStore().getKey(ALIAS, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            null
        } ?: throw KeysLostException()
        return try {
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeysLostException(e)
        } catch (e: java.security.InvalidKeyException) {
            throw KeysLostException(e)
        }
    }

    /** Call with the authenticated cipher from [cipherForCreate]. Returns the new data key. */
    fun completeCreate(cipher: Cipher): ByteArray {
        val dek = ByteArray(DEK_BYTES).also(random::nextBytes)
        val wrapped = cipher.doFinal(dek)
        writeAtomically(dekFile, cipher.iv + wrapped)
        stateFile.delete()
        return dek
    }

    /** Call with the authenticated cipher from [cipherForUnlock]. Returns the data key. */
    fun completeUnlock(cipher: Cipher): ByteArray {
        val blob = dekFile.readBytes()
        val dek = try {
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw KeysLostException(e)
        }
        if (dek.size != DEK_BYTES) throw KeysLostException()
        return dek
    }

    /** The saved plaintext state, or null if nothing has been saved. Throws [KeysLostException] if it cannot be opened. */
    fun load(dek: ByteArray): ByteArray? {
        if (!stateFile.exists()) return null
        val blob = try {
            stateFile.readBytes()
        } catch (e: IOException) {
            throw KeysLostException(e)
        }
        if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != STATE_VERSION) throw KeysLostException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_BYTES)))
            cipher.updateAAD(STATE_AAD)
            cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw KeysLostException(e)
        }
    }

    /** Encrypts [plain] under the data key (fresh random nonce every time) and replaces the state file atomically. */
    fun save(dek: ByteArray, plain: ByteArray) {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(STATE_AAD)
        writeAtomically(stateFile, byteArrayOf(STATE_VERSION) + iv + cipher.doFinal(plain))
    }

    /** Removes the Keystore key and both files. Used when keys are lost and when starting over. */
    fun wipe() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing to delete, or the Keystore is unavailable; the files below are useless without the key anyway.
        }
        dekFile.delete()
        stateFile.delete()
    }

    private fun generateKey() {
        fun spec(strongBox: Boolean): KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Timeout 0: the person must authenticate for every single use of this key.
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setUnlockedDeviceRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: Exception) {
            // No StrongBox on this phone (or it refused the options): use the normal secure hardware.
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "tenchar_vault_key_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val IV_BYTES = 12
        const val DEK_BYTES = 32
        const val STATE_VERSION: Byte = 1
        val STATE_AAD = "tenchar-state-v1".toByteArray(Charsets.UTF_8)
    }
}
