package maestro.debuglog

import ios.logger.Logger
import maestro.drivers.IOSDriver

class IOSDriverLogger : Logger {

    private val logger by lazy { DebugLogStore.loggerFor(IOSDriverLogger::class.java) }

    override fun info(message: String) {
        logger.info(message)
    }
}