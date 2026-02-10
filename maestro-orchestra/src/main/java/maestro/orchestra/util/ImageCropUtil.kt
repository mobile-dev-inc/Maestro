package maestro.orchestra.util

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class CropResult(
    val croppedPng: ByteArray,
    val cropX1: Int,
    val cropY1: Int,
    val cropX2: Int,
    val cropY2: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

object ImageCropUtil {

    fun cropAroundPoint(
        screenshotPng: ByteArray,
        centerXPercent: Int,
        centerYPercent: Int,
        cropPercent: Int = 30,
    ): CropResult {
        val image = ImageIO.read(ByteArrayInputStream(screenshotPng))
        val width = image.width
        val height = image.height

        val centerX = (centerXPercent * width) / 100
        val centerY = (centerYPercent * height) / 100

        val cropW = (cropPercent * width) / 100
        val cropH = (cropPercent * height) / 100

        val x1 = (centerX - cropW / 2).coerceIn(0, width - 1)
        val y1 = (centerY - cropH / 2).coerceIn(0, height - 1)
        val x2 = (x1 + cropW).coerceAtMost(width)
        val y2 = (y1 + cropH).coerceAtMost(height)

        val actualW = x2 - x1
        val actualH = y2 - y1

        if (actualW <= 0 || actualH <= 0) {
            return CropResult(
                croppedPng = screenshotPng,
                cropX1 = 0,
                cropY1 = 0,
                cropX2 = width,
                cropY2 = height,
                originalWidth = width,
                originalHeight = height,
            )
        }

        val cropped = image.getSubimage(x1, y1, actualW, actualH)
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(cropped, "png", outputStream)

        return CropResult(
            croppedPng = outputStream.toByteArray(),
            cropX1 = x1,
            cropY1 = y1,
            cropX2 = x2,
            cropY2 = y2,
            originalWidth = width,
            originalHeight = height,
        )
    }

    fun mapCroppedPercentToOriginalPercent(
        croppedXPercent: Int,
        croppedYPercent: Int,
        crop: CropResult,
    ): Pair<Int, Int> {
        val cropW = crop.cropX2 - crop.cropX1
        val cropH = crop.cropY2 - crop.cropY1

        val absoluteX = crop.cropX1 + (croppedXPercent * cropW) / 100
        val absoluteY = crop.cropY1 + (croppedYPercent * cropH) / 100

        val originalXPercent = ((absoluteX * 100) / crop.originalWidth).coerceIn(0, 100)
        val originalYPercent = ((absoluteY * 100) / crop.originalHeight).coerceIn(0, 100)

        return Pair(originalXPercent, originalYPercent)
    }
}
