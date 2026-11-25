package maestro.orchestra.error

import java.nio.file.Path

/**
 * One problem `check-syntax` found in a flow, at a 1-based position in [file]. [file] is the flow that was
 * checked, or a `runFlow` / `retry` target's own path when the problem was found through its parent;
 * [source] is that file's text, for the snippet. [endLine] / [endColumn] close the span, and [accepted] /
 * [suggestions] list the names allowed there and the ones close to what was written, when the check knows
 * them; the parser supplies none of them yet, so they are null and empty.
 */
data class SyntaxProblem(
    val file: Path,
    val line: Int,
    val column: Int,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val code: String,
    val title: String,
    val message: String,
    val accepted: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val docs: String? = null,
    val source: String,
) {
    /** Snippet, caret, message and docs link, as the parser's own errors render them. */
    fun renderDetail(): String = ParserErrorRenderer.renderDetail(
        ParserErrorContext(title, file.toString(), line, column, source, message, docs),
    )

    companion object {
        /** The part of [title] before the first `:`, lowercased, spaces to hyphens: `unknown-property`. */
        fun codeOf(title: String): String =
            title.substringBefore(':').trim().lowercase().replace(' ', '-')
    }
}
