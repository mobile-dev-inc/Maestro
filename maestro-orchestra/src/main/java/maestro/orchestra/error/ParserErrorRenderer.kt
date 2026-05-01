package maestro.orchestra.error

internal data class ParserErrorContext(
    val title: String,
    val filePath: String?,
    val line: Int,
    val column: Int,
    val flowSource: String,
    val message: String,
    val docs: String? = null,
)

/**
 * Renders parser errors as plain text that reads well in a terminal AND inside
 * a `<pre>` element on the web (Studio, copilot frontend). The output uses only
 * regular ASCII spaces and `\n` line breaks — no Unicode box-drawing, no
 * non-breaking spaces, no padded carets that depend on a specific font width.
 */
internal object ParserErrorRenderer {

    private const val LINES_BEFORE = 2
    private const val LINES_AFTER = 2
    private const val LINE_NUMBER_WIDTH = 4
    private const val INDENT = "  "

    fun renderPlain(ctx: ParserErrorContext): String {
        val sb = StringBuilder()
        sb.appendLine(ctx.title)
        sb.appendLine("${INDENT}at ${locationString(ctx)}")

        val snippet = renderSnippet(ctx)
        if (snippet.isNotEmpty()) {
            sb.appendLine()
            sb.append(snippet)
            sb.appendLine()
        }

        sb.appendLine()
        ctx.message.lines().forEach { line ->
            sb.appendLine("$INDENT$line")
        }
        if (!ctx.docs.isNullOrBlank()) {
            sb.appendLine("${INDENT}See: ${ctx.docs}")
        }

        return sb.toString().trimEnd()
    }

    private fun locationString(ctx: ParserErrorContext): String {
        val location = "${ctx.line}:${ctx.column}"
        return if (ctx.filePath != null) "${ctx.filePath}:$location" else "line $location"
    }

    private fun renderSnippet(ctx: ParserErrorContext): String {
        val lines = ctx.flowSource.lines()
        if (lines.isEmpty() || ctx.line <= 0) return ""

        val zeroBasedLine = ctx.line - 1
        if (zeroBasedLine > lines.lastIndex) return ""

        val from = (zeroBasedLine - LINES_BEFORE).coerceAtLeast(0)
        val to = (zeroBasedLine + LINES_AFTER).coerceAtMost(lines.lastIndex)

        val sb = StringBuilder()
        for (i in from..to) {
            val isError = i == zeroBasedLine
            val marker = if (isError) ">" else " "
            val number = (i + 1).toString().padStart(LINE_NUMBER_WIDTH)
            val content = lines[i].trimEnd()
            sb.appendLine("$INDENT$marker $number | $content")
            if (isError) {
                val gutterWidth = INDENT.length + 1 + 1 + LINE_NUMBER_WIDTH + 3
                val caretOffset = gutterWidth + (ctx.column - 1).coerceAtLeast(0)
                sb.appendLine(" ".repeat(caretOffset) + "^")
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
