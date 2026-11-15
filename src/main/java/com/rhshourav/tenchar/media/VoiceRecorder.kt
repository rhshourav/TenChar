package com.rhshourav.tenchar.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.rhshourav.tenchar.Constants
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records one voice message: AAC in an MP4 file, mono, 16 kHz, 24 kbit/s (about 180 KB a minute), at most two minutes.
 *
 * MediaRecorder needs a seekable file, so the recording is written to the app's private cache and overwritten with
 * zeros and deleted as soon as it has been read. Loudness is sampled while recording, for the live display and for the
 * 40-bar waveform that travels with the message, so the listener sees the shape without decoding the audio.
 */
class VoiceRecorder(private val context: Context) {
    class Recording(val audio: ByteArray, val durationMs: Int, val wave: ByteArray)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val peaks = ArrayList<Int>()

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds recorded so far. */
    val elapsedMs: Int get() = if (recorder == null) 0 else (System.nanoTime() / 1_000_000L - startedAt).toInt()

    /** Starts recording. [onLimit] is called on the main thread when the two-minute limit stops it. False if it cannot start. */
    fun start(onLimit: () -> Unit): Boolean {
        if (recorder != null) return false
        val target = File(context.cacheDir, "rec_${System.nanoTime()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(Constants.VOICE_SAMPLE_RATE)
            r.setAudioEncodingBitRate(Constants.VOICE_BITRATE)
            r.setMaxDuration(Constants.VOICE_MAX_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit()
            }
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            TempFiles.wipe(target)
            return false
        }
        recorder = r
        file = target
        peaks.clear()
        startedAt = System.nanoTime() / 1_000_000L
        return true
    }

    /** Loudness of the last moment, 0 to 1, and remembered for the waveform. Call about every 80 ms. */
    fun sample(): Float {
        val peak = try {
            recorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
        peaks.add(peak)
        return level(peak)
    }

    /** The loudness of the most recent [count] samples (oldest first), for the live display. */
    fun recent(count: Int): List<Float> = peaks.takeLast(count).map(::level)

    /** Stops and returns the recording, or null if it is too short or failed. The audio is wiped from disk either way. */
    fun finish(): Recording? {
        val r = recorder ?: return null
        val f = file
        val durationMs = elapsedMs
        recorder = null
        file = null
        val stopped = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // stopped before any audio was captured
        } finally {
            runCatching { r.release() }
        }
        if (f == null) return null
        try {
            if (!stopped || durationMs < Constants.VOICE_MIN_MS || !f.isFile || f.length() == 0L) return null
            val audio = f.readBytes()
            if (audio.size > Constants.MAX_MEDIA_BYTES) return null
            return Recording(audio, durationMs.coerceAtMost(Constants.VOICE_MAX_MS), waveform(peaks))
        } finally {
            TempFiles.wipe(f)
        }
    }

    /** Throws the recording away. */
    fun cancel() {
        val r = recorder
        val f = file
        recorder = null
        file = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        if (f != null) TempFiles.wipe(f)
    }

    private fun level(peak: Int): Float = sqrt((peak / 32767f).coerceIn(0f, 1f)) // closer to how loudness is heard

    companion object {
        /**
         * 40 bars of 4 bits (0 to 15), two to a byte, each the loudest moment of its share of the recording and scaled
         * so the loudest bar of the message is full height. A quiet recording still shows its shape.
         */
        fun waveform(peaks: List<Int>): ByteArray {
            val bars = Constants.WAVE_BARS
            val out = ByteArray(Constants.WAVE_BYTES)
            if (peaks.isEmpty()) return out
            val loudest = max(1, peaks.max())
            val levels = IntArray(bars) { bar ->
                val from = bar * peaks.size / bars
                val to = max(from + 1, (bar + 1) * peaks.size / bars).coerceAtMost(peaks.size)
                var m = 0
                for (i in from until to) m = max(m, peaks[i])
                (sqrt(m.toFloat() / loudest) * 15f).roundToInt().coerceIn(0, 15)
            }
            for (i in 0 until Constants.WAVE_BYTES) out[i] = ((levels[2 * i] shl 4) or levels[2 * i + 1]).toByte()
            return out
        }

        /** The bar heights, 0 to 1, of a stored waveform. Empty if there is none. */
        fun bars(wave: ByteArray): FloatArray {
            if (wave.size != Constants.WAVE_BYTES) return FloatArray(0)
            return FloatArray(Constants.WAVE_BARS) { i ->
                val b = wave[i / 2].toInt() and 0xFF
                (if (i % 2 == 0) b ushr 4 else b and 0x0F) / 15f
            }
        }
    }
}
