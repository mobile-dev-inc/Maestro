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

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object CommandLine {

    /**
     * Runs a shell command with optional arguments, working directory, environment, and timeout.
     *
     * @param command The command to run, as a string or list of arguments.
     * @param args Optional list of arguments (if command is a program name).
     * @param workingDirectory The working directory for the command, or null for current.
     * @param environment Environment variables to set, or null for default.
     * @param timeout Timeout in milliseconds, or null for no timeout.
     * @return The started [Process].
     * @throws TimeoutException If the process times out.
     * @throws Exception If the process fails to start.
     */
    fun runCommand(
        command: String,
        args: List<String>? = null,
        workingDirectory: String? = null,
        environment: Map<String, String>? = null,
        timeout: Long? = null,
    ): Process {
        val commandList =
            when {
                args != null -> parseCommand(command) + args
                else -> parseCommand(command)
            }
        val commandString = commandList.joinToString(" ")
        val processBuilder =
            ProcessBuilder(commandList).apply {
                workingDirectory?.let { directory(File(it)) }
                environment?.let { environment().putAll(it) }
                redirectErrorStream(false)
            }
        val process =
            try {
                processBuilder.start()
            } catch (e: Exception) {
                throw Exception("Failed to start command: $commandString", e)
            }

        if (timeout != null) {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw TimeoutException("Command '$commandString' timed out after $timeout ms")
            }
        } else {
            process.waitFor()
        }

        return process
    }

    /**
     * Parses a command string into a list of arguments, handling quotes and escapes. Trims input
     * and returns an empty list for blank/null input.
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
     * Escapes command line output for safe use in JavaScript and trims trailing whitespace.
     * - Escapes backslashes and double quotes.
     * - Converts newlines to \n.
     * - Removes carriage returns.
     * - Trims trailing whitespace.
     */
    fun escapeCommandLineOutput(output: String): String {
        return output.replace("\r\n", "\n") // Normalize Windows newlines
                .replace("\r", "\n") // Normalize old Mac newlines
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace(Regex("(\\\\n)+$"), "") // Remove trailing \n sequences
                .trim()
    }
}
