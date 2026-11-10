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

/**
 * Shows the system unlock prompt and calls back once the person is authenticated.
 *
 * [cipher] is null for the normal flow: the Keystore key is then valid for a few seconds after the prompt succeeds,
 * so nothing has to be bound to the prompt. It is not null only for a vault made by an earlier version, whose key
 * had to be used through the prompt's own CryptoObject (see [Vault.legacyCipherForUnlock]).
 */
fun interface Authenticator {
    fun authenticate(cipher: Cipher?, onSuccess: (Cipher?) -> Unit, onFailure: (cancelled: Boolean) -> Unit)
}

/**
 * Key hierarchy:
 *
 *   Android Keystore key "v2" (hardware-backed: the secure hardware, not StrongBox, see [generateKey])
 *     - AES-256-GCM, usable only for [AUTH_WINDOW_S] seconds after the person unlocks with a strong biometric or the
 *       screen lock, and only while the phone is unlocked
 *     - bound to the screen lock's secure id, so adding, removing or re-learning a fingerprint cannot invalidate it;
 *       Android destroys it only if the secure lock screen itself is removed
 *   wraps -> data key (32 random bytes, stored wrapped in tc_dek.bin)
 *   encrypts -> tc_state.bin (AES-256-GCM, the whole [AppState])
 *
 * Why not one prompt per use, as before: a key that has to be used through the prompt's CryptoObject is tied to the
 * fingerprint hardware's own identifier. On some phones, notably ones with an under-display sensor, that identifier
 * changes without anyone enrolling anything (for example when the sensor refreshes its stored template), and Android
 * then reports the key as permanently invalidated, which looked like "your fingerprint has changed" every time the app
 * was reopened. This app uses the key once per unlock, so the practical difference is a window of a few seconds after
 * a successful prompt, which the secure hardware still enforces.
 *
 * The data key lives in memory only while the app is unlocked. [lock] callers must overwrite it. Nothing secret is
 * ever written unencrypted.
 */
class Vault(context: Context) {
    private val dekFile = File(context.filesDir, "tc_dek.bin")
    private val stateFile = File(context.filesDir, "tc_state.bin")

    /** The previous saved state, kept so one bad write cannot cost the chats. */
    private val backupFile = File(context.filesDir, "tc_state.bak")
    private val random = SecureRandom()

    fun exists(): Boolean = dekFile.exists()

    /** True for a vault made by an earlier version: its wrapped key file has no version byte and its key is per-use. */
    fun isLegacy(): Boolean = dekFile.exists() && dekFile.length() == (IV_BYTES + DEK_BYTES + TAG_BYTES).toLong()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    // ---- creating and opening (current format) -----------------------------------------------------------------

    /** Makes a new Keystore key. Any old vault is discarded. Call [completeCreate] right after the person authenticates. */
    fun prepareCreate() {
        wipe()
        generateKey(ALIAS)
    }

    /** Wraps a new data key with the key from [prepareCreate]. Must run within [AUTH_WINDOW_S] seconds of the prompt succeeding. */
    fun completeCreate(): ByteArray {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeysLostException(KeysLostReason.KeyMissing)
        val dek = ByteArray(DEK_BYTES).also(random::nextBytes)
        writeAtomically(dekFile, wrap(key, dek))
        stateFile.delete()
        backupFile.delete()
        return dek
    }

    /**
     * The data key, unwrapped. Must run within [AUTH_WINDOW_S] seconds of the prompt succeeding.
     * Throws [KeysLostException] only when the key is certainly gone (its [KeysLostException.cause] says exactly what
     * Android reported); a prompt that has just expired or a Keystore hiccup is an ordinary exception, so the person
     * is only ever asked to try again.
     */
    fun completeUnlock(): ByteArray {
        val blob = try {
            dekFile.readBytes()
        } catch (e: IOException) {
            throw IllegalStateException("could not read the vault file", e)
        }
        if (blob.size != 1 + IV_BYTES + DEK_BYTES + TAG_BYTES || blob[0] != FILE_V2) {
            throw KeysLostException(KeysLostReason.VaultFileDamaged)
        }
        val key = try {
            keyStore().getKey(ALIAS, null) as? SecretKey
        } catch (e: java.security.UnrecoverableKeyException) {
            throw KeysLostException(KeysLostReason.KeyInvalidated, e)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("the Keystore is not available right now", e)
        } ?: throw KeysLostException(KeysLostReason.KeyMissing)
        return unwrap(key, blob)
    }

    private fun wrap(key: SecretKey, dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(DEK_AAD)
        val wrapped = cipher.doFinal(dek)
        return byteArrayOf(FILE_V2) + cipher.iv + wrapped
    }

    private fun unwrap(key: SecretKey, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_BYTES)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeysLostException(KeysLostReason.KeyInvalidated, e)
        }
        // Any other InvalidKeyException (for example "user not authenticated": the window has passed) is temporary.
        cipher.updateAAD(DEK_AAD)
        val dek = try {
            cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (e: javax.crypto.BadPaddingException) {
            // The authentication tag did not match: the file really is damaged. (AEADBadTagException is one of these.)
            throw KeysLostException(KeysLostReason.VaultFileDamaged, e)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("could not unwrap the data key", e)
        }
        if (dek.size != DEK_BYTES) throw KeysLostException(KeysLostReason.VaultFileDamaged)
        return dek
    }

    // ---- a vault from an earlier version -----------------------------------------------------------------------

    /**
     * A cipher that will unwrap the old data key once the person authenticates through the prompt with it.
     * Throws [KeysLostException] only when the key is certainly gone; any other problem is an ordinary exception.
     */
    fun legacyCipherForUnlock(): Cipher {
        val blob = try {
            dekFile.readBytes()
        } catch (e: IOException) {
            throw IllegalStateException("could not read the vault file", e)
        }
        if (blob.size != IV_BYTES + DEK_BYTES + TAG_BYTES) throw KeysLostException(KeysLostReason.VaultFileDamaged)
        val key = try {
            keyStore().getKey(LEGACY_ALIAS, null) as? SecretKey
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
    }

    /** Call with the authenticated cipher from [legacyCipherForUnlock]. Returns the data key. */
    fun completeLegacyUnlock(cipher: Cipher): ByteArray {
        val blob = dekFile.readBytes()
        val dek = try {
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: javax.crypto.BadPaddingException) {
            throw KeysLostException(KeysLostReason.VaultFileDamaged, e)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("could not unwrap the data key", e)
        }
        if (dek.size != DEK_BYTES) throw KeysLostException(KeysLostReason.VaultFileDamaged)
        return dek
    }

    /**
     * Re-wraps [dek] under a new-style key right after a legacy unlock, so the next unlock no longer depends on the
     * fingerprint hardware's identifier. The old file and key are replaced only after the new wrap has been read back
     * successfully. Returns false, changing nothing that matters, if anything goes wrong; the next unlock tries again.
     */
    fun migrateFromLegacy(dek: ByteArray): Boolean = try {
        generateKey(ALIAS)
        val key = keyStore().getKey(ALIAS, null) as SecretKey
        val wrapped = wrap(key, dek)
        val check = unwrap(key, wrapped)
        val same = check.contentEquals(dek)
        check.fill(0)
        if (same) {
            writeAtomically(dekFile, wrapped)
            try {
                keyStore().deleteEntry(LEGACY_ALIAS)
            } catch (e: Exception) {
                // The old key is useless once its file is gone.
            }
        }
        same
    } catch (e: Exception) {
        false
    }

    // ---- the saved chats -----------------------------------------------------------------------------------------

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

    /** Removes the Keystore keys and every file. Only ever called because the person chose to start over. */
    fun wipe() {
        for (alias in listOf(ALIAS, LEGACY_ALIAS)) {
            try {
                keyStore().deleteEntry(alias)
            } catch (e: Exception) {
                // Nothing to delete, or the Keystore is unavailable; the files below are useless without the key anyway.
            }
        }
        dekFile.delete()
        stateFile.delete()
        backupFile.delete()
    }

    /**
     * The Keystore key. [PREFER_STRONGBOX] is off on purpose: a StrongBox key that fails at use time (some phones do
     * this with time-limited keys) cannot be replaced without losing the chats, and the key is already held in the
     * phone's secure hardware.
     */
    private fun generateKey(alias: String) {
        fun spec(strongBox: Boolean): KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // A short window after any strong unlock (fingerprint, face, or the screen lock) opens the key.
            .setUserAuthenticationParameters(
                AUTH_WINDOW_S,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .setUnlockedDeviceRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        if (PREFER_STRONGBOX) {
            try {
                generator.init(spec(strongBox = true))
                generator.generateKey()
                return
            } catch (e: Exception) {
                // No StrongBox on this phone: use the normal secure hardware.
            }
        }
        generator.init(spec(strongBox = false))
        generator.generateKey()
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** Seconds after a successful prompt during which the Keystore key may be used. */
        const val AUTH_WINDOW_S = 10

        private const val PREFER_STRONGBOX = false
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "tenchar_vault_key_v2"
        private const val LEGACY_ALIAS = "tenchar_vault_key_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val IV_BYTES = 12
        private const val DEK_BYTES = 32
        private const val FILE_V2: Byte = 2
        private const val STATE_VERSION: Byte = 1
        private val STATE_AAD = "tenchar-state-v1".toByteArray(Charsets.UTF_8)
        private val DEK_AAD = "tenchar-dek-v2".toByteArray(Charsets.UTF_8)
    }
}
