package maestro.orchestra.backend

/**
 * Thrown BELOW the seam when a backend has no verb for a capability the router asked for (device-core
 * has no screenshot/recording primitive). A later router task catches this and records it as a coverage
 * gap rather than a crash; this task only defines and throws it.
 */
class BackendUnsupportedOperation(message: String) : RuntimeException(message)
