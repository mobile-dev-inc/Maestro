package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.ViewHierarchy
import okio.Buffer
import okio.Sink
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class ScreenshotUtils {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ScreenshotUtils::class.java)

        fun takeScreenshot(out: Sink, compressed: Boolean, driver: Driver) {
            LOGGER.trace("Taking screenshot to output sink")

            driver.takeScreenshot(out, compressed)
        }

        fun takeScreenshot(compressed: Boolean, driver: Driver): ByteArray {
            LOGGER.trace("Taking screenshot to byte array")

            val buffer = Buffer()
            takeScreenshot(buffer, compressed, driver)

            return buffer.readByteArray()
        }

        fun tryTakingScreenshot(driver: Driver) = try {
            ImageIO.read(takeScreenshot(true, driver).inputStream())
        } catch (e: Exception) {
            LOGGER.warn("Failed to take screenshot", e)
            null
        }

        /**
         * Variant that bounds the underlying driver call at [callTimeoutMs] ms.
         *
         * On drivers that ignore the timeout (Android/web today), behavior is identical to
         * [tryTakingScreenshot]. On iOS, the OkHttp call to XCUITest's `/screenshot`
         * endpoint is bounded so a single slow response can't outlive the user's deadline.
         */
        fun tryTakingScreenshot(driver: Driver, callTimeoutMs: Long?): BufferedImage? = try {
            val buffer = Buffer()
            driver.takeScreenshot(buffer, true, callTimeoutMs)
            ImageIO.read(buffer.readByteArray().inputStream())
        } catch (e: Exception) {
            LOGGER.warn("Failed to take screenshot", e)
            null
        }

        fun waitForAppToSettle(
            initialHierarchy: ViewHierarchy?,
            driver: Driver,
            timeoutMs: Int? = null
        ): ViewHierarchy {
            var latestHierarchy: ViewHierarchy
            if (timeoutMs != null) {
                val endTime = System.currentTimeMillis() + timeoutMs
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver)
                do {
                    val hierarchyAfter = viewHierarchy(driver)
                    if (latestHierarchy == hierarchyAfter) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            return hierarchyAfter
                        }
                    }
                    latestHierarchy = hierarchyAfter
                } while (System.currentTimeMillis() < endTime)
            } else {
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver)
                repeat(10) {
                    val hierarchyAfter = viewHierarchy(driver)
                    if (latestHierarchy == hierarchyAfter) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            return hierarchyAfter
                        }
                    }
                    latestHierarchy = hierarchyAfter

                    MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                }
            }

            return latestHierarchy
        }

        fun waitUntilScreenIsStatic(timeoutMs: Long, threshold: Double, driver: Driver): Boolean {
            // Deadline-aware loop: each call to the driver receives the remaining budget so
            // a single slow `takeScreenshot` (e.g. iOS XCUITest stalling /screenshot during
            // an animation) cannot blow past the user-supplied [timeoutMs]. Drivers that
            // don't honor the timeout (Android, web) will still finish in their own time,
            // but the second screenshot is never started once the deadline has passed and
            // the loop exits as soon as the body returns.
            val deadline = System.currentTimeMillis() + timeoutMs
            do {
                val remainingForFirst = deadline - System.currentTimeMillis()
                if (remainingForFirst <= 0) return false
                val startScreenshot: BufferedImage? = tryTakingScreenshot(driver, remainingForFirst)

                val remainingForSecond = deadline - System.currentTimeMillis()
                if (remainingForSecond <= 0) return false
                val endScreenshot: BufferedImage? = tryTakingScreenshot(driver, remainingForSecond)

                if (startScreenshot != null &&
                    endScreenshot != null &&
                    startScreenshot.width == endScreenshot.width &&
                    startScreenshot.height == endScreenshot.height
                ) {
                    val imageDiff = ImageComparison(
                        startScreenshot,
                        endScreenshot
                    ).compareImages().differencePercent

                    if (imageDiff <= threshold) return true
                }
            } while (System.currentTimeMillis() < deadline)
            return false
        }

        private fun viewHierarchy(driver: Driver): ViewHierarchy {
            return ViewHierarchy.from(driver, false)
        }
    }
}
