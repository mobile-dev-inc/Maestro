package maestro.cli.util

import maestro.cli.CliError
import java.io.File

object EnvFileParser {

    private val VALID_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Parses a .env file into a map of key-value pairs.
     *
     * Supported syntax:
     * - KEY=VALUE
     * - KEY="VALUE"
     * - KEY='VALUE'
     */
    fun parseEnvFile(file: File): Map<String, String> {
        if (!file.exists()) {
            throw CliError("Env file does not exist: ${file.absolutePath}")
        }
        if (!file.isFile) {
            throw CliError("Env file is not a regular file: ${file.absolutePath}")
        }

        val result = mutableMapOf<String, String>()
        file.readLines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            // Skip lines starting with "export " (common in shell env files) and strip the prefix
            val effectiveLine = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line

            val eqIndex = effectiveLine.indexOf('=')
            if (eqIndex <= 0) {
                throw CliError("Malformed line ${index + 1} in env file ${file.name}: $rawLine")
            }

            val key = effectiveLine.substring(0, eqIndex).trim()
            if (!key.matches(VALID_KEY_REGEX)) {
                throw CliError("Invalid variable name '${key}' at line ${index + 1} in env file ${file.name}")
            }

            val rawValue = effectiveLine.substring(eqIndex + 1)
            val value = unquote(rawValue)
            result[key] = value
        }
        return result
    }

    /**
     * Resolves the final environment map by merging env file values with
     * explicitly provided -e values. Explicit -e values take precedence.
     */
    fun resolveEnv(envFile: File?, env: Map<String, String>): Map<String, String> {
        if (envFile == null) return env
        val fromFile = parseEnvFile(envFile)
        return fromFile + env // env (from -e) overrides file values
    }

    private fun unquote(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
                (trimmed.startsWith('\'') && trimmed.endsWith('\''))) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }
}