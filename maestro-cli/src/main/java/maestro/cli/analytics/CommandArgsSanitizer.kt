package maestro.cli.analytics

/**
 * Sanitizes raw CLI argv into a single-line, PII-free string suitable for analytics.
 *
 * The output is attached to every CLI PostHog event via [Analytics.commandString] so we can
 * query "which flags are being passed" without leaking customer secrets or file paths.
 *
 * Rules:
 *  - Sensitive flags ([SENSITIVE_FLAGS]) have their values replaced with `<REDACTED>`.
 *  - Positional arguments (tokens that don't start with `-` and aren't consumed as a value)
 *    are dropped entirely — they routinely contain customer-named paths and product names.
 *  - `--flag=value` is split on the first `=`.
 *  - `--flag value` consumes the next token as the value when:
 *      * the flag is sensitive (always consume so we can redact), OR
 *      * the next token doesn't look like a filesystem path (no `/`, `\`, or known
 *        file extension). This is the heuristic that lets `--include-tags smoke` keep
 *        `smoke` while `--async flows/` correctly drops `flows/`. Without picocli's
 *        flag taxonomy we can't distinguish boolean-followed-by-positional from
 *        valued-flag perfectly; the path heuristic matches Maestro's actual positional
 *        shapes (workspace dirs and `.yaml`/`.apk`/`.ipa`/`.app` files).
 *  - Output is prefixed with `maestro` and joined with single spaces. Empty argv → `"maestro"`.
 */
object CommandArgsSanitizer {

    private const val REDACTED = "<REDACTED>"

    private val SENSITIVE_FLAGS: Set<String> = setOf(
        "--api-key",
        "--apiKey",
        "-e",
        "--env",
        "--name",
        "--app-binary-id",
        "--appBinaryId",
    )

    private val PATH_LIKE_EXTENSIONS: Set<String> = setOf(
        "yaml", "yml", "apk", "app", "ipa", "json", "zip", "xml", "txt",
    )

    fun sanitize(argv: Array<String>): String {
        if (argv.isEmpty()) return "maestro"

        val out = mutableListOf<String>()
        out += "maestro"
        out += argv[0]

        var i = 1
        while (i < argv.size) {
            val token = argv[i]

            if (!token.startsWith("-")) {
                // Positional that wasn't consumed by a preceding flag — drop it.
                i++
                continue
            }

            val eqIdx = token.indexOf('=')
            if (eqIdx >= 0) {
                val flagName = token.substring(0, eqIdx)
                val value = if (flagName in SENSITIVE_FLAGS) REDACTED else token.substring(eqIdx + 1)
                out += "$flagName=$value"
                i++
                continue
            }

            // Space-separated form: peek next token.
            val next = argv.getOrNull(i + 1)
            val isSensitive = token in SENSITIVE_FLAGS
            val shouldConsumeNext = next != null
                && !next.startsWith("-")
                && (isSensitive || !looksLikePath(next))

            if (shouldConsumeNext) {
                val value = if (isSensitive) REDACTED else next!!
                out += token
                out += value
                i += 2
            } else {
                // Boolean flag (or trailing flag whose "value" looks like a positional path).
                out += token
                i++
            }
        }

        return out.joinToString(" ")
    }

    private fun looksLikePath(token: String): Boolean {
        if (token.contains('/') || token.contains('\\')) return true
        val dotIdx = token.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == token.length - 1) return false
        val ext = token.substring(dotIdx + 1).lowercase()
        return ext in PATH_LIKE_EXTENSIONS
    }
}
