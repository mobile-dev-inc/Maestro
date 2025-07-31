package maestro.plugins

/**
 * Exception thrown when plugin validation fails.
 */
class PluginValidationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when plugin execution fails.
 */
class PluginExecutionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when plugin loading fails.
 */
class PluginLoadingException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when plugin registration fails.
 */
class PluginRegistrationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
