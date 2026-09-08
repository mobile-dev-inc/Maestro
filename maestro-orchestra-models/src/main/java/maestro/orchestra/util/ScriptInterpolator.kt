package maestro.orchestra.util

private const val BLOCK_OPEN = "\${"
private const val VALUE_ENDING = ")]}'\"`"

/**
 * Replaces every `${ script }` in [input] with [evaluate]'s result; `\${ ... }` stays literal.
 *
 * The closing brace is found by scanning brace depth, not by regex: a regex match is greedy and
 * takes the last `}` in the string, so any later brace broke the block. Braces
 * inside string, template, comment and regex literals are not counted.
 */
internal fun interpolate(input: String, evaluate: (String) -> String): String {
    if (!input.contains(BLOCK_OPEN)) return input

    val out = StringBuilder(input.length)
    var i = 0

    while (i < input.length) {
        val currentChar = input[i]

        if (currentChar == '\\' && input.startsWith(BLOCK_OPEN, i + 1)) {
            val close = findClosingBrace(input, i + 1 + BLOCK_OPEN.length)
            if (close == -1) {
                out.append(currentChar)
                i++
            } else {
                out.append(input, i + 1, close + 1)
                i = close + 1
            }
            continue
        }
        if (input.startsWith(BLOCK_OPEN, i)) {
            val close = findClosingBrace(input, i + BLOCK_OPEN.length)
            if (close == -1) {
                out.append(currentChar)
                i++
            } else {
                val script = input.substring(i + BLOCK_OPEN.length, close)
                if (script.isNotBlank()) out.append(evaluate(script))
                i = close + 1
            }
            continue
        }
        out.append(currentChar)
        i++
    }
    return out.toString()
}

/** Index OF the brace closing the block whose body starts at [from], or -1 if it never closes. */
private fun findClosingBrace(expression: String, from: Int): Int {
    val stack = ArrayDeque<Char>()
    stack.addLast('{')

    var i = from
    var lastSignificant = -1

    while (i < expression.length) {
        if (stack.last() == '`') {
            when {
                expression[i] == '\\' -> i += 2
                expression[i] == '`' -> {
                    stack.removeLast()
                    lastSignificant = i
                    i++
                }
                expression.startsWith(BLOCK_OPEN, i) -> {
                    stack.addLast('{')
                    i += BLOCK_OPEN.length
                }
                else -> i++
            }
            continue
        }
        val currentChar = expression[i]
        when {
            currentChar == '{' -> {
                stack.addLast('{')
                lastSignificant = i
                i++
            }
            currentChar == '}' -> {
                stack.removeLast()
                if (stack.isEmpty()) return i
                lastSignificant = i
                i++
            }
            currentChar == '`' -> {
                stack.addLast('`')
                i++
            }
            currentChar == '\'' || currentChar == '"' -> {
                i = skipString(expression, i, currentChar)
                lastSignificant = i - 1 // the closing quote: skipString returned the index past it
            }
            expression.startsWith("//", i) -> i = skipLineComment(expression, i)
            expression.startsWith("/*", i) -> i = skipBlockComment(expression, i)
            currentChar == '/' && regexCanStart(expression, lastSignificant) -> {
                i = skipRegex(expression, i)
                lastSignificant = i - 1 // the closing slash: skipRegex returned the index past it
            }
            currentChar.isWhitespace() -> i++
            else -> {
                lastSignificant = i
                i++
            }
        }
    }
    return -1
}

/**
* Every `skip*` below returns the index just PAST the run it skipped.
*/
private fun skipString(expression: String, open: Int, quote: Char): Int {
    var i = open + 1
    while (i < expression.length) {
        when (expression[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            '\n' -> return i
            else -> i++
        }
    }
    return expression.length
}

private fun skipLineComment(expression: String, open: Int): Int {
    val end = expression.indexOf('\n', open)
    return if (end == -1) expression.length else end
}

private fun skipBlockComment(expression: String, open: Int): Int {
    val end = expression.indexOf("*/", open + 2)
    return if (end == -1) expression.length else end + 2
}

private fun skipRegex(expression: String, open: Int): Int {
    var i = open + 1
    var inCharClass = false
    while (i < expression.length) {
        when (expression[i]) {
            '\\' -> i += 2
            '[' -> {
                inCharClass = true
                i++
            }
            ']' -> {
                inCharClass = false
                i++
            }
            '/' -> if (inCharClass) i++ else return i + 1
            '\n' -> return open + 1
            else -> i++
        }
    }
    return open + 1
}

/**
 * Can a regex literal start here? `/` is also division and only a full JS parser can be sure;
 * the rule is that a regex cannot follow a value. `}` is read as ending an object literal.
 */
private fun regexCanStart(expression: String, lastSignificant: Int): Boolean {
    if (lastSignificant < 0) return true

    val previous = expression[lastSignificant]
    if (previous in VALUE_ENDING) return false
    if (previous.isJsIdentifierChar()) {
        var start = lastSignificant
        while (start > 0 && expression[start - 1].isJsIdentifierChar()) start--
        return expression.substring(start, lastSignificant + 1) in KEYWORDS_BEFORE_REGEX
    }
    return true
}

private fun Char.isJsIdentifierChar() = isLetterOrDigit() || this == '_' || this == '$'

private val KEYWORDS_BEFORE_REGEX = setOf(
    "return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
    "case", "do", "else", "yield", "await",
)
