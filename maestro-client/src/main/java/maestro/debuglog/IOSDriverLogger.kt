package maestro.debuglog

import maestro.logger.Logger

class IOSDriverLogger(val clazz: Class<*>) : Logger {

    override fun info(message: String) {
        loggerFor(clazz).info(message)
    }

    companion object {
        private var loggers = mutableMapOf<Class<*>, java.util.logging.Logger>()

        fun loggerFor(clazz: Class<*>): java.util.logging.Logger {
            if (!loggers.containsKey(clazz)) {
                loggers[clazz] = DebugLogStore.loggerFor(clazz)
            }
            return loggers[clazz]!!
        }
    }
}
