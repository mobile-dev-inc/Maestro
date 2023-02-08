package maestro.drivers.screenshot

import maestro.Driver
import maestro.ViewHierarchy
import maestro.utils.MaestroTimer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IOSScreenshotDriver(override val driver: Driver) : ScreenshotDriver {
    override val logger: Logger
        get() = LoggerFactory.getLogger(IOSScreenshotDriver::class.java)
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return MaestroTimer.retryUntilTrue(timeoutMs) {
            val isScreenStatic = driver.isScreenStatic()

            logger.info("screen static = $isScreenStatic")
            return@retryUntilTrue isScreenStatic
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?): ViewHierarchy {
        logger.info("Waiting for animation to end with timeout $SCREEN_SETTLE_TIMEOUT_MS")
        val didFinishOnTime = waitUntilScreenIsStatic(SCREEN_SETTLE_TIMEOUT_MS)

        return if (didFinishOnTime) viewHierarchy() else genericWaitForAppToSettle(initialHierarchy)
    }

    companion object {
        private const val SCREEN_SETTLE_TIMEOUT_MS: Long = 2000
    }
}