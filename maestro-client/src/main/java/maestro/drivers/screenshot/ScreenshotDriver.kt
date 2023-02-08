package maestro.drivers.screenshot

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.ViewHierarchy
import maestro.utils.MaestroTimer
import okio.Buffer
import okio.Sink
import org.slf4j.Logger
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

interface ScreenshotDriver {
    val logger: Logger
    val driver: Driver

    fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean
    fun waitForAppToSettle(initialHierarchy: ViewHierarchy?): ViewHierarchy
}

fun ScreenshotDriver.takeScreenshot(out: Sink, compressed: Boolean) {
    logger.info("Taking screenshot to output sink")

    driver.takeScreenshot(out, compressed)
}

fun ScreenshotDriver.takeScreenshot(compressed: Boolean): ByteArray {
    logger.info("Taking screenshot to byte array")

    val buffer = Buffer()
    takeScreenshot(buffer, compressed)

    return buffer.readByteArray()
}

fun ScreenshotDriver.tryTakingScreenshot() = try {
    ImageIO.read(takeScreenshot(true).inputStream())
} catch (e: Exception) {
    logger.warn("Failed to take screenshot", e)
    null
}

fun ScreenshotDriver.genericWaitForAppToSettle(initialHierarchy: ViewHierarchy?): ViewHierarchy {
    var latestHierarchy = initialHierarchy ?: viewHierarchy()
    repeat(10) {
        val hierarchyAfter = viewHierarchy()
        if (latestHierarchy == hierarchyAfter) {
            val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
            if (!isLoading) {
                return hierarchyAfter
            }
        }
        latestHierarchy = hierarchyAfter

        MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
    }

    return latestHierarchy
}

fun ScreenshotDriver.genericWaitUntilScreenIsStatic(timeoutMs: Long, threshold: Double): Boolean {
    return MaestroTimer.retryUntilTrue(timeoutMs) {
        val startScreenshot: BufferedImage? = tryTakingScreenshot()
        val endScreenshot: BufferedImage? = tryTakingScreenshot()

        if (startScreenshot != null &&
            endScreenshot != null &&
            startScreenshot.width == endScreenshot.width &&
            startScreenshot.height == endScreenshot.height
        ) {
            val imageDiff = ImageComparison(
                startScreenshot,
                endScreenshot
            ).compareImages().differencePercent

            return@retryUntilTrue imageDiff <= threshold
        }

        return@retryUntilTrue false
    }
}

fun ScreenshotDriver.viewHierarchy(): ViewHierarchy {
    return ViewHierarchy.from(driver)
}