package maestro.cli

class CliError(override val message: String) : RuntimeException(message)
