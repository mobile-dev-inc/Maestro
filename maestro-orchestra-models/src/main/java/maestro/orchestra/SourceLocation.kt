package maestro.orchestra

data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
)
