package com.rhshourav.tenchar.notify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.rhshourav.tenchar.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the alert service needs while the app is locked: for each chat, the relays, the room id and the watch token.
 *
 * The chats themselves stay inside the vault and cannot be opened without the fingerprint or screen lock. This is a
 * separate, deliberately small file. The watch token can only ask "how many messages wait?", so what it exposes if
 * someone gets at it is which relays and rooms this phone checks, which the relay operator already sees. It is
 * encrypted with a hardware-backed Keystore key that needs no unlock (the service must run while the phone is locked),
 * so another app or a copy of the files cannot read it.
 */
object WatchStore {
    class Watch(val room: String, val token: String, val endpoints: List<String>, val notified: LongArray)

    private const val FILE = "tc_watch.bin"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "tenchar_watch_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private val AAD = "tenchar-watch-v1".toByteArray(Charsets.UTF_8)
    private val lock = Any()

    fun load(context: Context): List<Watch> = synchronized(lock) { read(context) }

    /** Replaces the list, keeping what is already known about which messages were announced. */
    fun replace(context: Context, watches: List<Watch>) = synchronized(lock) {
        val old = read(context)
        val merged = watches.map { w ->
            val before = old.firstOrNull { it.room == w.room && it.token == w.token }
            val notified = LongArray(w.endpoints.size) { i ->
                val at = before?.endpoints?.indexOf(w.endpoints[i]) ?: -1
                if (before != null && at >= 0) before.notified[at] else 0L
            }
            Watch(w.room, w.token, w.endpoints, notified)
        }
        write(context, merged)
    }

    /** Remembers that messages up to [latest] on [endpoint] have been announced, so each is announced once. */
    fun markNotified(context: Context, room: String, endpoint: String, latest: Long) = synchronized(lock) {
        val all = read(context)
        val w = all.firstOrNull { it.room == room } ?: return@synchronized
        val i = w.endpoints.indexOf(endpoint)
        if (i >= 0 && latest > w.notified[i]) {
            w.notified[i] = latest
            write(context, all)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        File(context.filesDir, FILE).delete()
    }

    // ---- storage ----------------------------------------------------------------------------------------------

    private fun read(context: Context): List<Watch> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        return try {
            val blob = file.readBytes()
            if (blob.size < 1 + IV_BYTES + 16 || blob[0] != VERSION) return emptyList()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(1, 1 + IV_BYTES)))
            cipher.updateAAD(AAD)
            decode(cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES))
        } catch (e: Exception) {
            emptyList() // unreadable (for example the key was lost): the app writes a fresh list when it unlocks
        }
    }

    private fun write(context: Context, watches: List<Watch>) {
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val data = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(encode(watches))
            val target = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Alerts are a convenience; the chats are not affected.
        }
    }

    private fun encode(watches: List<Watch>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(watches.size)
        for (w in watches) {
            out.write(Hex.decode(w.room))
            out.write(Hex.decode(w.token))
            out.writeByte(w.endpoints.size)
            for (i in w.endpoints.indices) {
                out.writeUTF(w.endpoints[i])
                out.writeLong(w.notified[i])
            }
        }
        out.flush()
        return bytes.toByteArray()
    }

    private fun decode(data: ByteArray): List<Watch> {
        val input = DataInputStream(ByteArrayInputStream(data))
        val count = input.readUnsignedByte()
        if (count > 32) throw IOException("bad count")
        return List(count) {
            val room = Hex.encode(ByteArray(16).also(input::readFully))
            val token = Hex.encode(ByteArray(32).also(input::readFully))
            val n = input.readUnsignedByte()
            if (n > 8) throw IOException("bad endpoint count")
            val endpoints = ArrayList<String>(n)
            val notified = LongArray(n)
            for (i in 0 until n) {
                endpoints.add(input.readUTF())
                notified[i] = input.readLong()
            }
            Watch(room, token, endpoints, notified)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
