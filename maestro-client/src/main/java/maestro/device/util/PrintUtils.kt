package maestro.device.util

import org.slf4j.LoggerFactory

/**
 * Simplified version of PrintUtils for DeviceService
 */
object PrintUtils {
    private val logger = LoggerFactory.getLogger(PrintUtils::class.java)

    fun message(message: String) {
        logger.info(message)
    }
}
