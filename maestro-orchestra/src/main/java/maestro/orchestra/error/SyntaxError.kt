package maestro.orchestra.error

class SyntaxError(
    override val message: String,
    detail: String? = null,
    cause: Throwable? = null,
) : ValidationError(message, detail, cause)
