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
        validateQuality(format, quality)

        val outputStream = ByteArrayOutputStream()
        val ok = try {
            bitmap.compress(format, quality, outputStream)
        } catch (t: Throwable) {
            throw ScreenshotException(
                message = "Bitmap compression failed: format=${format.name}, width=${bitmap.width}, height=${bitmap.height}, config=${bitmap.config}",
                cause = t
            )
        }

        if (!ok) {
            throw ScreenshotException(
                message = "Bitmap.compress returned false: format=${format.name}, quality=$quality, width=${bitmap.width}, height=${bitmap.height}, config=${bitmap.config}"
            )
        }

        val bytes = outputStream.toByteArray()
        if (bytes.isEmpty()) {
            throw ScreenshotException(
                message = "Bitmap compressed but produced empty output: format=${format.name}, quality=$quality, width=${bitmap.width}, height=${bitmap.height}, config=${bitmap.config}"
            )
        }
        return ByteString.copyFrom(bytes)
    }

    private fun validateQuality(format: Bitmap.CompressFormat, quality: Int) {
        if (quality !in 0..100) {
            throw IllegalArgumentException("quality must be in 0..100, got $quality")
        }
    }
}
