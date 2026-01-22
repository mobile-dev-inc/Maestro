package maestro.orchestra.error

/**
 * Exception thrown when both the main flow and onFlowComplete fail.
 * Preserves the original flow exception as the primary cause while tracking
 * the onFlowComplete failure separately.
 */
class OnFlowCompleteFailedException(
    val originalException: Throwable,
    val onFlowCompleteException: Throwable
) : RuntimeException(originalException.message, originalException)
