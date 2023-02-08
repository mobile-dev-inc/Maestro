package maestro.drivers.screenshot

import maestro.Driver
import maestro.ViewHierarchy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GenericScreenshotDriver(override val driver: Driver) : ScreenshotDriver {
    override val logger: Logger
        get() = LoggerFactory.getLogger(GenericScreenshotDriver::class.java)
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return genericWaitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD)
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?): ViewHierarchy {
        return genericWaitForAppToSettle(initialHierarchy)
    }

    companion object {
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
    }
}