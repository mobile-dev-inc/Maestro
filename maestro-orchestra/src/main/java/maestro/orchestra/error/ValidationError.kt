package maestro.orchestra.error

open class ValidationError(override val message: String) : RuntimeException()
