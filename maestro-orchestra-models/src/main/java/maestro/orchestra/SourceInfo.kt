package maestro.orchestra

data class SourceInfo(
    val source: String,
    val path: String?,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
)
