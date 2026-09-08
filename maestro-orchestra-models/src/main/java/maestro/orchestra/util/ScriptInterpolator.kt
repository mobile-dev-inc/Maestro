package maestro.orchestra.util

internal fun interpolate(input: String, evaluate: (String) -> String): String {
    if (input.contains("\${")) return input

    val out = StringBuilder(input.length)
    var i = 0

    while (i < input.length) {
        val currentChar = input[i]

        if (currentChar == '\\' && input.startsWith("\${", i + 1)) {
            val close =  findClosingBrace(input, i + 3)
            if (close == -1) {
                out.append(currentChar)
                i++
            } else {
                out.append(input, i + 1, close + 1)
                i = close + 1
            }
            continue
        }
        if (currentChar == '$' && input.getOrNull(i + 1) == '{'){
            val close = findClosingBrace(input, i + 2)
            if (close == -1) {
                out.append(currentChar)
                i++
            } else {
                val script = input.substring(i + 2, close)
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
                expression[i] == '$' && expression.getOrNull(i + 1) == '{' -> {
                    stack.addLast('{')
                    i += 2
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
                lastSignificant = i - 1
            }
            currentChar == '/' && expression.getOrNull(i + 1) == '/' -> i = skipLineComment(expression, i)

            currentChar == '/' && expression.getOrNull(i + 1) == '*' -> i = skipBlockComment(expression, i)

            currentChar == '/' && regexCanStart(expression, lastSignificant) -> {
                i = skipRegex(expression, i)
                lastSignificant = i - 1
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
            '[' -> { inCharClass = true; i++ }
            ']' -> { inCharClass = false; i++ }
            '/' -> if (inCharClass) i++ else return i + 1
            '\n' -> return open + 1
            else -> i++
        }
    }
    return open + 1
}

private fun regexCanStart(expression: String, lastSignificant: Int): Boolean {
    if (lastSignificant < 0) return true

    val currentChar = expression[lastSignificant]
    if (currentChar == ')'
        || currentChar == ']'
        || currentChar == '}'
        || currentChar == '\''
        || currentChar == '"'
        || currentChar == '`')
        return false
    if (currentChar.isLetterOrDigit() || currentChar == '_' || currentChar == '$') {
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