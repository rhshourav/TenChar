package com.rhshourav.tenchar.store

import android.content.Context
import com.rhshourav.tenchar.crypto.BlobCrypto
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The attachments on this phone, one encrypted file each, in the app's private storage.
 *
 * A file here is byte for byte what the relay holds (see [BlobCrypto]): ciphertext under the attachment's own key,
 * which lives only inside the encrypted chat state. Nothing is ever stored in the clear. The names are random-looking
 * ids derived from the keys. Everything here is blocking file work: call it from a background dispatcher.
 */
class MediaStore(context: Context) {
    private val dir = File(context.filesDir, "media")

    fun file(id: String): File = File(dir, "$id.tcm")

    /** Where a download is written until it has been checked. */
    fun partFile(id: String): File = File(dir, "$id.part").also { dir.mkdirs() }

    fun exists(id: String): Boolean = file(id).isFile

    /** Encrypts [plain] under [att]'s key into the store. Replaces nothing that is already complete. */
    fun write(att: Attachment, plain: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "${att.id}.tmp")
        try {
            BufferedOutputStream(FileOutputStream(tmp)).use { out -> BlobCrypto.encrypt(att.key, att.kind, plain, out) }
            Files.move(tmp.toPath(), file(att.id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Checks a finished download against the attachment's key, kind and size, and only then makes it the real file.
     * False (and the download is deleted) if it is not exactly what the sender encrypted.
     */
    fun commitDownload(att: Attachment): Boolean {
        val part = partFile(att.id)
        try {
            val plain = FileInputStream(part).use { BlobCrypto.decrypt(att.key, att.kind, att.size, it) } ?: return false
            plain.fill(0)
            Files.move(part.toPath(), file(att.id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: IOException) {
            return false
        } finally {
            part.delete()
        }
    }

    /** The content, decrypted into memory, or null if the file is missing or does not verify. The caller wipes it. */
    fun open(att: Attachment): ByteArray? = try {
        FileInputStream(file(att.id)).use { BlobCrypto.decrypt(att.key, att.kind, att.size, it) }
    } catch (e: IOException) {
        null
    }

    fun delete(id: String) {
        file(id).delete()
        File(dir, "$id.part").delete()
        File(dir, "$id.tmp").delete()
    }

    /**
     * Deletes the files nothing refers to any more: a finished file whose id is not in [keep] (its chat or message is
     * gone), and a partial one ([.part], [.tmp]) left behind by an interrupted transfer. A partial file touched in the
     * last [STALE_MS] is left alone, because a transfer may be writing it right now.
     */
    fun keepOnly(keep: Set<String>) {
        val names = dir.list() ?: return
        val now = System.currentTimeMillis()
        for (name in names) {
            val f = File(dir, name)
            if (name.endsWith(".tcm")) {
                if (name.substringBefore('.') !in keep) f.delete()
            } else if (now - f.lastModified() > STALE_MS) {
                f.delete()
            }
        }
    }

    fun wipeAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val STALE_MS = 10 * 60 * 1000L
    }
}
