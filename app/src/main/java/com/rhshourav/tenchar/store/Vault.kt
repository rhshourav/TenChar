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

/** Why the saved data cannot be opened. Shown to the person, because it tells them (and anyone helping) what happened. */
enum class KeysLostReason {
    /** Android permanently invalidated the key (for example the screen lock was removed). */
    KeyInvalidated,

    /** The key is no longer in the Keystore. */
    KeyMissing,

    /** The wrapped data key on disk is damaged. */
    VaultFileDamaged,

    /** The data key is fine, but no saved copy of the chats can be read. */
    StateUnreadable,
}

/**
 * The saved data cannot be opened, and trying again will not help. This is **never** a reason to delete anything by
 * itself: the files stay where they are until the person chooses to erase them. Anything temporary (a Keystore
 * hiccup, a failed read) is thrown as an ordinary exception instead, so it only ever shows "try again".
 */
class KeysLostException(val reason: KeysLostReason, cause: Throwable? = null) : Exception("keys lost: $reason", cause)

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

    /** The previous saved state, kept so one bad write cannot cost the chats. */
    private val backupFile = File(context.filesDir, "tc_state.bak")
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

    /**
     * A cipher that will unwrap the stored data key once the person authenticates.
     * Throws [KeysLostException] only when the key is certainly gone; any other problem is an ordinary exception.
     */
    fun cipherForUnlock(): Cipher {
        val blob = try {
            dekFile.readBytes()
        } catch (e: IOException) {
            throw IllegalStateException("could not read the vault file", e) // may pass: not a reason to give up
        }
        if (blob.size != IV_BYTES + DEK_BYTES + TAG_BYTES) throw KeysLostException(KeysLostReason.VaultFileDamaged)
        val key = try {
            keyStore().getKey(ALIAS, null) as? SecretKey
        } catch (e: java.security.UnrecoverableKeyException) {
            throw KeysLostException(KeysLostReason.KeyInvalidated, e)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("the Keystore is not available right now", e)
        } ?: throw KeysLostException(KeysLostReason.KeyMissing)
        return try {
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeysLostException(KeysLostReason.KeyInvalidated, e)
        }
        // Any other InvalidKeyException (for example "not authenticated yet") is temporary and propagates as is.
    }

    /** Call with the authenticated cipher from [cipherForCreate]. Returns the new data key. */
    fun completeCreate(cipher: Cipher): ByteArray {
        val dek = ByteArray(DEK_BYTES).also(random::nextBytes)
        val wrapped = cipher.doFinal(dek)
        writeAtomically(dekFile, cipher.iv + wrapped)
        stateFile.delete()
        backupFile.delete()
        return dek
    }

    /** Call with the authenticated cipher from [cipherForUnlock]. Returns the data key. */
    fun completeUnlock(cipher: Cipher): ByteArray {
        val blob = dekFile.readBytes()
        val dek = try {
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: javax.crypto.BadPaddingException) {
            // The authentication tag did not match: the file really is damaged. (AEADBadTagException is one of these.)
            throw KeysLostException(KeysLostReason.VaultFileDamaged, e)
        } catch (e: GeneralSecurityException) {
            // Anything else after a successful fingerprint is a hiccup, not proof that the data is gone.
            throw IllegalStateException("could not unwrap the data key", e)
        }
        if (dek.size != DEK_BYTES) throw KeysLostException(KeysLostReason.VaultFileDamaged)
        return dek
    }

    /**
     * The saved state, decrypted: the current file first, then the backup of the one before it. Empty if nothing was ever
     * saved. A file that will not decrypt is skipped, not deleted; if files exist but none can be read this throws
     * [KeysLostException] with [KeysLostReason.StateUnreadable].
     */
    fun loadCandidates(dek: ByteArray): List<ByteArray> {
        val found = ArrayList<ByteArray>()
        var any = false
        for (file in listOf(stateFile, backupFile)) {
            if (!file.exists()) continue
            any = true
            decryptOrNull(file, dek)?.let { found += it }
        }
        if (any && found.isEmpty()) throw KeysLostException(KeysLostReason.StateUnreadable)
        return found
    }

    private fun decryptOrNull(file: File, dek: ByteArray): ByteArray? = try {
        val blob = file.readBytes()
        if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != STATE_VERSION) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_BYTES)))
            cipher.updateAAD(STATE_AAD)
            cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        }
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    /** Encrypts [plain] under the data key (fresh random nonce every time) and replaces the state file atomically. */
    fun save(dek: ByteArray, plain: ByteArray) {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(STATE_AAD)
        val data = byteArrayOf(STATE_VERSION) + iv + cipher.doFinal(plain)
        // Keep the file being replaced as a backup, but only if it is itself readable: never let a damaged file
        // overwrite a good backup.
        if (stateFile.exists() && decryptOrNull(stateFile, dek)?.also { it.fill(0) } != null) {
            Files.copy(stateFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        writeAtomically(stateFile, data)
    }

    /** Removes the Keystore key and every file. Only ever called because the person chose to start over. */
    fun wipe() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing to delete, or the Keystore is unavailable; the files below are useless without the key anyway.
        }
        dekFile.delete()
        stateFile.delete()
        backupFile.delete()
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
            // Adding a fingerprint or face must not destroy the key. With the default (true) Android invalidates a
            // per-use key on biometric enrollment, which looked to the person like "unlocking erased my chats". The
            // screen-lock PIN still unlocks the same key, so someone who can enrol a fingerprint could already unlock.
            .setInvalidatedByBiometricEnrollment(false)
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