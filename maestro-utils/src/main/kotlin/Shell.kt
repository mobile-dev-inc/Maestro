/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.utils

/**
 * Parses a command string into a list of arguments, handling quotes and escapes.
 * Trims input and returns an empty list for blank/null input.
 */
fun parseCommand(cmd: String?): List<String> {
    val line = cmd?.trim().orEmpty()
    if (line.isEmpty()) return emptyList()

    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    var escape = false

    line.forEach { c ->
        when {
            escape -> {
                current.append(c)
                escape = false
            }
            c == '\\' -> escape = true
            c == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
            c == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
            c.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                if (current.isNotEmpty()) {
                    args += current.toString()
                    current.clear()
                }
            }
            else -> current.append(c)
        }
    }
    if (current.isNotEmpty()) {
        args += current.toString()
    }
    return args
}

/**
 * Escapes shell output for safe use in JavaScript and trims trailing whitespace.
 * - Escapes backslashes and double quotes.
 * - Converts newlines to \n.
 * - Removes carriage returns.
 * - Trims trailing whitespace.
 */
fun escapeShellOutput(output: String): String {
    return output
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n")
        .trimEnd()
}