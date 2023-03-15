package maestro.debuglog

import maestro.logger.Logger

class IOSDriverLogger : Logger {

    companion object {
        private val logger by lazy { DebugLogStore.loggerFor(IOSDriverLogger::class.java) }
    }

    override fun info(message: String) {
        logger.info(message)
    }
}
