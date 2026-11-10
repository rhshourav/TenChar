package com.rhshourav.tenchar.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.rhshourav.tenchar.Constants
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shrinks a picked video to what can be sent: H.264 with AAC audio, shortest side at most 480 px, about 0.8 Mbit/s,
 * so 30 seconds is roughly 3 MB. This uses AndroidX Media3 Transformer (the one dependency this feature adds).
 *
 * The transcoded file has to exist as a file while it is being written (the MP4 writer needs to seek), so it goes in
 * the app's private cache and is overwritten and deleted as soon as it has been read ([wipe]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoTranscoder {
    /** Size as it appears on screen (rotation applied) and length. */
    class Info(val width: Int, val height: Int, val durationMs: Long)

    /** Blocking. Null if the file cannot be read as a video. */
    fun probe(context: Context, uri: Uri): Info? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            if (rotation == 90 || rotation == 270) Info(h, w, ms) else Info(w, h, ms)
        } catch (e: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

    /** Where to write the result. */
    fun tempFile(context: Context): File = File(context.cacheDir, "vid_${System.nanoTime()}.mp4")

    /** Transcodes [uri] into [out]. True on success. Cancelling the coroutine cancels the transcode. */
    suspend fun transcode(context: Context, uri: Uri, info: Info, out: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            var transformer: Transformer? = null
            main.post {
                try {
                    val effects = ArrayList<Effect>()
                    val short = min(info.width, info.height)
                    if (short > Constants.VIDEO_SHORT_SIDE) {
                        val scale = Constants.VIDEO_SHORT_SIDE.toFloat() / short
                        effects += Presentation.createForWidthAndHeight(
                            even(info.width * scale), even(info.height * scale), Presentation.LAYOUT_SCALE_TO_FIT,
                        )
                    }
                    val encoders = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(Constants.VIDEO_BITRATE).build(),
                        )
                        .build()
                    val t = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoders)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    transformer = t
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setEffects(Effects(emptyList(), effects))
                        .build()
                    t.start(item, out.absolutePath)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            cont.invokeOnCancellation { main.post { transformer?.cancel() } }
        }

    private fun even(value: Float): Int = max(2, (value.roundToInt() / 2) * 2)

    /** Overwrites a temporary file with zeros, then deletes it. */
    fun wipe(file: File) = TempFiles.wipe(file)
}
