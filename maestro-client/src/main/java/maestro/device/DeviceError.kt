package maestro.device

/**
 * Exception class specifically for device-related errors in the client module.
 * Functionally equivalent to CliError in maestro-cli.
 */
class DeviceError(override val message: String) : RuntimeException(message)
