package maestro.orchestra.error

open class ValidationError(
    override val message: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun ValidationError.formatForTerminal(): String =
    if (detail.isNullOrBlank()) message else "$message\n\n$detail"
