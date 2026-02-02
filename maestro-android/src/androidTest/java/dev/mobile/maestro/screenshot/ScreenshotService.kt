package dev.mobile.maestro.screenshot

import android.graphics.Bitmap
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream

/**
 * Thrown when screenshot encoding fails (e.g. [Bitmap.compress] throws or returns false).
 */
class ScreenshotException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Encodes screenshots to PNG (or other formats) with validation and size limits.
 */
class ScreenshotService() {

    /**
     * Encodes a screenshot bitmap to PNG bytes.
     *
     * @throws ScreenshotException if compression fails or output is invalid/too large.
     */
    fun encodePng(bitmap: Bitmap, quality: Int = 100): ByteString =
        encode(bitmap, Bitmap.CompressFormat.PNG, quality)

    /**
     * Generic encoder for any [Bitmap.CompressFormat].
     *
     * @throws ScreenshotException if compression fails or output is invalid/too large.
     */
    fun encode(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int = 100,
    ): ByteString {
        validateBitmap(bitmap)
        validateQuality(format, quality)

        val outputStream = ByteArrayOutputStream()
        val ok = try {
            bitmap.compress(format, quality, outputStream)
        } catch (t: Throwable) {
            throw ScreenshotException(
                message = "Bitmap compression failed (${format.name})",
                cause = t
            )
        }

        if (!ok) {
            throw ScreenshotException(message = "Failed to compress bitmap (${format.name})")
        }

        val bytes = outputStream.toByteArray()
        if (bytes.isEmpty()) {
            throw ScreenshotException(
                message = "Bitmap compressed but produced empty output (${format.name})"
            )
        }
        return ByteString.copyFrom(bytes)
    }

    private fun validateBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            throw ScreenshotException(message = "Bitmap is recycled and cannot be compressed")
        }
    }

    private fun validateQuality(format: Bitmap.CompressFormat, quality: Int) {
        if (quality !in 0..100) {
            throw IllegalArgumentException("quality must be in 0..100, got $quality")
        }
    }
}
