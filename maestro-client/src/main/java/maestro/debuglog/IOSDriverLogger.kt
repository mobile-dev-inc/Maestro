package maestro.debuglog

import maestro.logger.Logger

class IOSDriverLogger : Logger {

    private val logger by lazy { DebugLogStore.loggerFor(IOSDriverLogger::class.java) }

    override fun info(message: String) {
        logger.info(message)
    }
}