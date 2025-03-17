package maestro.orchestra.error

open class ValidationError(override val message: String, cause: Throwable? = null) : RuntimeException(message, cause)
