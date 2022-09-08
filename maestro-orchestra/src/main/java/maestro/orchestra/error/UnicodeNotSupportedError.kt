package maestro.orchestra.error

data class UnicodeNotSupportedError(
    val text: String,
) : RuntimeException("Unicode not supported: $text")