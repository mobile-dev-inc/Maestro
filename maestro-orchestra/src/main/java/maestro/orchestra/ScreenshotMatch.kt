package maestro.orchestra

import com.github.romankh3.image.comparison.ImageComparison
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Single source of truth for screenshot comparison: owns both the
 * pass/fail decision *and* the "current %" that gets reported back to the
 * user. Keeping these in one place prevents the historical bug where the
 * threshold was evaluated against one metric (count of pixels exceeding
 * `pixelToleranceLevel`) while the message reported a different one
 * (`ImageComparisonResult.differencePercent`, an average RGB intensity).
 *
 * The underlying library is still used to draw the diff PNG, but no longer
 * decides whether the screenshot matches.
 */
internal object ScreenshotMatch {

    const val DEFAULT_PIXEL_TOLERANCE: Double = 0.1

    sealed class Result {
        data class Match(val matchPercent: Double) : Result()
        data class Mismatch(val matchPercent: Double) : Result()
        data class SizeMismatch(
            val expectedWidth: Int,
            val expectedHeight: Int,
            val actualWidth: Int,
            val actualHeight: Int,
        ) : Result()
    }

    /**
     * Compare [actual] against [expected] and decide if it meets [thresholdPercentage].
     * On [Result.Mismatch], writes a rectangles-overlay PNG to [diffFile] as a side effect.
     */
    fun compare(
        expected: BufferedImage,
        actual: BufferedImage,
        thresholdPercentage: Double,
        diffFile: File,
        pixelToleranceLevel: Double = DEFAULT_PIXEL_TOLERANCE,
    ): Result {
        if (expected.width != actual.width || expected.height != actual.height) {
            return Result.SizeMismatch(
                expectedWidth = expected.width,
                expectedHeight = expected.height,
                actualWidth = actual.width,
                actualHeight = actual.height,
            )
        }

        val matchPct = matchPercentage(expected, actual, pixelToleranceLevel)
        if (matchPct >= thresholdPercentage) {
            return Result.Match(matchPct)
        }

        // Use ImageComparison purely as a diff renderer. We've already decided this is a
        // mismatch above; passing `100 - thresholdPercentage` keeps the library's internal
        // gate consistent with our decision so it produces and writes the rectangles overlay.
        ImageComparison(expected, actual, diffFile).apply {
            allowingPercentOfDifferentPixels = 100.0 - thresholdPercentage
            this.pixelToleranceLevel = pixelToleranceLevel
            rectangleLineWidth = 10
            minimalRectangleSize = 40
        }.compareImages()

        return Result.Mismatch(matchPct)
    }

    /**
     * Percentage of pixels in [actual] that match [expected] within [pixelToleranceLevel],
     * using the same Euclidean color-distance rule as
     * `com.github.romankh3.image.comparison.ImageComparison`.
     */
    fun matchPercentage(
        expected: BufferedImage,
        actual: BufferedImage,
        pixelToleranceLevel: Double = DEFAULT_PIXEL_TOLERANCE,
    ): Double {
        require(expected.width == actual.width && expected.height == actual.height) {
            "matchPercentage requires images of the same size: " +
                    "expected=${expected.width}x${expected.height}, " +
                    "actual=${actual.width}x${actual.height}"
        }
        val width = expected.width
        val height = expected.height
        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels == 0L) return 100.0

        val differenceConstant = (pixelToleranceLevel * sqrt(255.0 * 255.0 * 3)).pow(2)
        var differing = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val e = expected.getRGB(x, y)
                val a = actual.getRGB(x, y)
                if (e == a) continue
                if (pixelToleranceLevel == 0.0) {
                    differing++
                    continue
                }
                val dr = ((a shr 16) and 0xff) - ((e shr 16) and 0xff)
                val dg = ((a shr 8) and 0xff) - ((e shr 8) and 0xff)
                val db = (a and 0xff) - (e and 0xff)
                val sqDist = (dr * dr + dg * dg + db * db).toDouble()
                if (sqDist > differenceConstant) differing++
            }
        }
        return 100.0 - (differing.toDouble() / totalPixels) * 100.0
    }
}
