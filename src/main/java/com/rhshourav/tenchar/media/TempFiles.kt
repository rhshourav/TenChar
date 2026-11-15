package com.rhshourav.tenchar.media

import java.io.File
import java.io.RandomAccessFile

/** Short-lived plaintext files (a recording being written, a video being transcoded) are zeroed before they are deleted. */
object TempFiles {
    fun wipe(file: File) {
        try {
            if (file.isFile) {
                RandomAccessFile(file, "rw").use { raf ->
                    val zeros = ByteArray(64 * 1024)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
            }
        } catch (e: Exception) {
            // Best effort: the file is in private storage and is deleted next.
        } finally {
            file.delete()
        }
    }

    /** Removes leftovers of an interrupted recording or transcode. */
    fun sweep(cacheDir: File) {
        cacheDir.listFiles { f -> f.name.startsWith("rec_") || f.name.startsWith("vid_") }?.forEach { wipe(it) }
    }
}
