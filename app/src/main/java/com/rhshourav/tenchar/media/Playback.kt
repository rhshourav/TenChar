package com.rhshourav.tenchar.media

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.LruCache
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rhshourav.tenchar.store.Attachment
import com.rhshourav.tenchar.store.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets MediaPlayer and MediaMetadataRetriever read decrypted content straight from memory, so it never touches disk. */
class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/**
 * Plays one voice message at a time from memory. [playingId] and [progress] are Compose state, so the bubble that is
 * playing redraws its waveform as it goes.
 */
class VoicePlayer {
    var playingId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private var player: MediaPlayer? = null
    private var data: ByteArray? = null
    private var ticker: Job? = null

    /** Starts [id], or pauses / resumes it if it is the one already loaded. */
    fun toggle(id: String, audio: ByteArray, scope: CoroutineScope) {
        val current = player
        if (playingId == id && current != null) {
            if (current.isPlaying) {
                current.pause()
                playing = false
            } else {
                current.start()
                playing = true
            }
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(ByteArrayDataSource(audio))
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            p.setOnPreparedListener {
                it.start()
                playing = true
            }
            player = p
            data = audio
            playingId = id
            progress = 0f
            p.prepareAsync()
            ticker = scope.launch {
                while (isActive) {
                    val live = player
                    if (live != null && playing) {
                        val length = live.duration
                        if (length > 0) progress = (live.currentPosition.toFloat() / length).coerceIn(0f, 1f)
                    }
                    delay(50)
                }
            }
        } catch (e: Exception) {
            runCatching { p.release() }
            stop()
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.release() } }
        player = null
        data?.fill(0)
        data = null
        playingId = null
        playing = false
        progress = 0f
    }
}

/**
 * Decrypts attachments for display. Decoded pictures are kept in a small in-memory cache, which [clear] empties when the
 * app locks. Nothing here writes to disk.
 */
object MediaLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun clear() = cache.evictAll()

    /** The decrypted content, or null if the file is missing or does not verify. The caller wipes it when done. */
    suspend fun bytes(store: MediaStore, att: Attachment): ByteArray? = withContext(Dispatchers.IO) { store.open(att) }

    /** A picture scaled so its longest side is about [maxEdge] pixels. */
    suspend fun image(store: MediaStore, att: Attachment, maxEdge: Int): Bitmap? {
        val key = "${att.id}:$maxEdge"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = store.open(att) ?: return@withContext null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { cache.put(key, it) }
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** The first frame of a video, about [width] pixels wide. */
    suspend fun videoFrame(store: MediaStore, att: Attachment, width: Int): Bitmap? {
        val key = "${att.id}:v$width"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = store.open(att) ?: return@withContext null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ByteArrayDataSource(bytes))
                val height = (width * att.height.toFloat() / att.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                    ?.also { cache.put(key, it) }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { mmr.release() }
                bytes.fill(0)
            }
        }
    }
}
