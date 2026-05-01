package maestro.orchestra.error

/**
 * @param message Short, single-line summary suitable for a status bar
 *   (e.g. `"Config Field Required at /flow.yaml:2:1"`). Always populated.
 * @param detail  Optional rich block — code snippet with caret, the human
 *   message, an optional docs link — suitable for a `<pre>` panel or for
 *   appending to terminal output. Null when the error has no extra context
 *   to show beyond [message].
 */
open class ValidationError(
    override val message: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Combined summary + detail, separated by a blank line. This is what the
 * CLI prints to the terminal and what the parser snapshot tests assert
 * against — equivalent to the old single-string error output.
 */
fun ValidationError.formatForTerminal(): String =
    if (detail.isNullOrBlank()) message else "$message\n\n$detail"
