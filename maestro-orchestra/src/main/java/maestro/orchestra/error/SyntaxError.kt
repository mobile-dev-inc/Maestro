package maestro.orchestra.error

class SyntaxError(override val message: String) : ValidationError(message)