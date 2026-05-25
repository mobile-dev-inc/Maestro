package maestro.orchestra

import com.github.romankh3.image.comparison.ImageComparison
import com.github.romankh3.image.comparison.model.ImageComparisonState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Coverage for [ScreenshotMatch] — the single source of truth that backs the
 * assertScreenshot command. The same function decides pass/fail *and* produces
 * the "current %" reported in the failure message, so the threshold check and
 * the user-visible number can never drift.
 */
class AssertScreenshotMatchTest {

    @TempDir
    lateinit var tempDir: Path

    private fun loadResource(name: String) =
        ImageIO.read(javaClass.getResourceAsStream("/AssertScreenshotMatchTest/$name")!!)

    private fun diffFile(name: String = "diff.png"): File = tempDir.resolve(name).toFile()

    @Test
    fun `compare returns Match when match percent meets threshold`() {
        val expected = loadResource("expected.png")
        val actual = loadResource("actual.png")

        val result = ScreenshotMatch.compare(
            expected = expected,
            actual = actual,
            thresholdPercentage = 95.0,
            diffFile = diffFile(),
        )

        assertThat(result).isInstanceOf(ScreenshotMatch.Result.Match::class.java)
        assertThat((result as ScreenshotMatch.Result.Match).matchPercent).isWithin(0.01).of(99.9511)
    }

    @Test
    fun `compare returns Mismatch when match percent falls below threshold and writes diff file`() {
        val expected = loadResource("expected.png")
        val actual = loadResource("actual.png")
        val diff = diffFile()

        val result = ScreenshotMatch.compare(
            expected = expected,
            actual = actual,
            thresholdPercentage = 99.99,
            diffFile = diff,
        )

        assertThat(result).isInstanceOf(ScreenshotMatch.Result.Mismatch::class.java)
        assertThat((result as ScreenshotMatch.Result.Mismatch).matchPercent).isWithin(0.01).of(99.9511)
        assertThat(diff.exists()).isTrue()
    }

    @Test
    fun `compare returns Mismatch for visually distinct screens of the same size`() {
        val expected = loadResource("actual.png")
        val actual = loadResource("saved_without_search.png")
        val diff = diffFile()

        val result = ScreenshotMatch.compare(
            expected = expected,
            actual = actual,
            thresholdPercentage = 95.0,
            diffFile = diff,
        )

        val mismatch = result as ScreenshotMatch.Result.Mismatch
        assertThat(mismatch.matchPercent).isWithin(0.01).of(91.4724)
        assertThat(diff.exists()).isTrue()
    }

    @Test
    fun `compare returns SizeMismatch for differently sized images`() {
        val expected = loadResource("expected.png")
        val actual = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)

        val result = ScreenshotMatch.compare(
            expected = expected,
            actual = actual,
            thresholdPercentage = 95.0,
            diffFile = diffFile(),
        )

        val sizeMismatch = result as ScreenshotMatch.Result.SizeMismatch
        assertThat(sizeMismatch.expectedWidth).isEqualTo(expected.width)
        assertThat(sizeMismatch.expectedHeight).isEqualTo(expected.height)
        assertThat(sizeMismatch.actualWidth).isEqualTo(100)
        assertThat(sizeMismatch.actualHeight).isEqualTo(100)
    }

    /**
     * Pins our pixel-walk to the upstream library at the threshold boundary.
     * If the library's `pixelToleranceLevel` semantics ever change, the
     * library's MATCH/MISMATCH decision will diverge from ours and this test
     * will catch it.
     */
    @Test
    fun `match percentage agrees with library MATCH MISMATCH decision at the boundary`() {
        val expected = loadResource("expected.png")
        val actual = loadResource("actual.png")

        for (pixelToleranceLevel in listOf(0.0, 0.05, 0.1)) {
            val ourMatchPct = ScreenshotMatch.matchPercentage(expected, actual, pixelToleranceLevel)
            val ourDiffPct = 100.0 - ourMatchPct

            val justAbove = libraryDecision(expected, actual, pixelToleranceLevel, ourDiffPct + 0.001)
            val justBelow = libraryDecision(expected, actual, pixelToleranceLevel, ourDiffPct - 0.001)

            assertThat(justAbove).isEqualTo(ImageComparisonState.MATCH)
            assertThat(justBelow).isEqualTo(ImageComparisonState.MISMATCH)
        }
    }

    @Test
    fun `match percentage exposes count-based metric distinct from library's average color delta`() {
        val expected = loadResource("expected.png")
        val actual = loadResource("actual.png")

        // At pixelToleranceLevel=0.0, ~0.5749% of pixels differ → match% ≈ 99.4251%.
        val ourMatchPct = ScreenshotMatch.matchPercentage(expected, actual, pixelToleranceLevel = 0.0)
        assertThat(ourMatchPct).isWithin(0.01).of(99.4251)

        // The library's `differencePercent` (avg per-channel RGB delta) is much smaller (~0.0366%)
        // and is NOT a meaningful "current %" against the configured threshold. This is the bug
        // we removed by owning the metric ourselves.
        val cmp = ImageComparison(expected, actual)
            .setAllowingPercentOfDifferentPixels(0.1)
            .setPixelToleranceLevel(0.0)
        val libraryResult = cmp.compareImages()
        assertThat(libraryResult.imageComparisonState).isEqualTo(ImageComparisonState.MISMATCH)
        val misleadingMatchPct = 100.0 - libraryResult.differencePercent
        assertThat(misleadingMatchPct).isGreaterThan(ourMatchPct + 0.5)
    }

    private fun libraryDecision(
        expected: BufferedImage,
        actual: BufferedImage,
        pixelToleranceLevel: Double,
        allowingPercentOfDifferentPixels: Double,
    ): ImageComparisonState {
        return ImageComparison(expected, actual)
            .setAllowingPercentOfDifferentPixels(allowingPercentOfDifferentPixels)
            .setPixelToleranceLevel(pixelToleranceLevel)
            .compareImages()
            .imageComparisonState
    }

}
