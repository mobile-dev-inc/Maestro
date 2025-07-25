package maestro.orchestra.error

class SyntaxError(override val message: String, cause: Throwable? = null) : ValidationError(message, cause)