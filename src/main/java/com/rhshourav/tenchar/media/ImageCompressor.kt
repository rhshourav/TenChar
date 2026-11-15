package com.rhshourav.tenchar.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import com.rhshourav.tenchar.Constants
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked photo into the JPEG that is sent.
 *
 * The photo is decoded straight to at most [Constants.IMAGE_MAX_EDGE] pixels on its longest side (so a 50-megapixel
 * original never sits in memory), turned upright according to its EXIF orientation, and written out again as a new
 * JPEG at quality 75. Writing it out again is what removes the metadata: location, camera, time and the rest of the
 * EXIF block never leave the phone. If the result is still over [Constants.IMAGE_TARGET_BYTES] the quality drops to
 * 70, then the picture shrinks by 15% steps. Expect a 12-megapixel phone photo (3 to 5 MB) to come out around
 * 150 to 300 KB, which is 90% or more smaller (an estimate, not a guarantee: a picture that is already small and
 * heavily compressed saves less).
 */
object ImageCompressor {
    class Result(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Blocking: call from a background dispatcher. Null if the file cannot be read as an image. */
    fun compress(resolver: ContentResolver, uri: Uri): Result? {
        var bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > Constants.IMAGE_MAX_EDGE) {
                    val scale = Constants.IMAGE_MAX_EDGE.toFloat() / longest
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        bitmap = flatten(bitmap)

        var quality = Constants.IMAGE_QUALITY_START
        var jpeg = encode(bitmap, quality) ?: return null
        var steps = 0
        while (jpeg.size > Constants.IMAGE_TARGET_BYTES && steps < MAX_STEPS) {
            if (quality > Constants.IMAGE_QUALITY_MIN) {
                quality = Constants.IMAGE_QUALITY_MIN
            } else {
                val smaller = Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * SHRINK).roundToInt()),
                    max(1, (bitmap.height * SHRINK).roundToInt()),
                    true,
                )
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
            jpeg = encode(bitmap, quality) ?: return null
            steps++
        }
        val result = Result(jpeg, bitmap.width, bitmap.height)
        bitmap.recycle()
        return result
    }

    /** JPEG has no transparency: put a transparent picture on white instead of letting it turn black. */
    private fun flatten(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        source.recycle()
        return out
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream(64 * 1024)
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
    }

    private const val MAX_STEPS = 6
    private const val SHRINK = 0.85f
}
