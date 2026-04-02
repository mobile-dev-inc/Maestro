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
     *
     * @param file The .env file to parse.
     * @return A map of environment variables defined in the file.
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

            val eqIndex = line.indexOf('=')
            if (eqIndex <= 0) {
                throw CliError("Malformed line ${index + 1} in env file ${file.name}: $rawLine")
            }

            val key = line.substring(0, eqIndex).trim()
            if (!key.matches(VALID_KEY_REGEX)) {
                throw CliError("Invalid variable name '${key}' at line ${index + 1} in env file ${file.name}")
            }

            val rawValue = line.substring(eqIndex + 1)
            val value = unquote(rawValue)
            result[key] = value
        }
        return result
    }

    /**
     * Resolves the final environment map by merging env file values with
     * explicitly provided -e values. Explicit -e values take precedence.
     *
     * @param envFile The .env file to parse, or null if not provided.
     * @param envMap The map of environment variables provided via -e options.
     * @return A merged map of environment variables.
     */
    fun resolveEnv(envFile: File?, envMap: Map<String, String>): Map<String, String> {
        if (envFile == null) return envMap
        val fromFile = parseEnvFile(file = envFile)
        return fromFile + envMap // envMap (from -e) overrides file values
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