package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal object McpImages {
    // Vision models (e.g. Claude) reject images whose longest side exceeds 2000px,
    // so screenshots are downscaled to this limit before being returned. See issue #2952.
    private const val MAX_IMAGE_DIMENSION = 2000
    private const val JPEG_QUALITY = 0.9f

    /** A screenshot as MCP image content: JPEG, longest side within [MAX_IMAGE_DIMENSION]. */
    fun screenshotContent(pngBytes: ByteArray): ImageContent =
        ImageContent(data = toJpegBase64(pngBytes), mimeType = "image/jpeg")

    private fun toJpegBase64(pngBytes: ByteArray): String {
        // Convert PNG to JPEG, downscaling so the longest side stays within
        // MAX_IMAGE_DIMENSION. Drawing onto an RGB canvas applies the scale and
        // flattens any alpha channel, which JPEG cannot represent.
        val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
            ?: error("Failed to decode screenshot image")

        val longestSide = maxOf(pngImage.width, pngImage.height)
        val scale = if (longestSide > MAX_IMAGE_DIMENSION) {
            MAX_IMAGE_DIMENSION.toDouble() / longestSide
        } else {
            1.0
        }
        val targetWidth = (pngImage.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (pngImage.height * scale).toInt().coerceAtLeast(1)

        val jpegImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = jpegImage.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(pngImage, 0, 0, targetWidth, targetHeight, null)
        graphics.dispose()

        val jpegOutput = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        ImageIO.createImageOutputStream(jpegOutput).use { imageOutputStream ->
            writer.output = imageOutputStream
            writer.write(null, IIOImage(jpegImage, null, null), params)
        }
        writer.dispose()

        return Base64.getEncoder().encodeToString(jpegOutput.toByteArray())
    }
}
