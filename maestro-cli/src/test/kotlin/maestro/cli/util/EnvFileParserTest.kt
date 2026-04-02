package maestro.cli.util

import maestro.cli.CliError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class EnvFileParserTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createEnvFile(content: String, name: String = ".env"): File {
        val file = tempDir.resolve(name).toFile()
        file.writeText(content)
        return file
    }

    @Test
    fun `parse basic KEY=VALUE pairs`() {
        val file = createEnvFile(
            """
            API_URL=https://api.example.com
            API_KEY=abc123
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals(mapOf(
            "API_URL" to "https://api.example.com",
            "API_KEY" to "abc123"
        ), result)
    }

    @Test
    fun `parse double-quoted values`() {
        val file = createEnvFile(
            """
            GREETING="Hello World"
            EMPTY=""
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("Hello World", result["GREETING"])
        assertEquals("", result["EMPTY"])
    }

    @Test
    fun `parse single-quoted values`() {
        val file = createEnvFile(
            """
             GREETING='Hello World'
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("Hello World", result["GREETING"])
    }

    @Test
    fun `skip comments and blank lines`() {
        val file = createEnvFile(
            """
            # Comment 1
            
            KEY1=value1
            
            # Comment 2
            KEY2=value2
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals(mapOf("KEY1" to "value1", "KEY2" to "value2"), result)
    }

    @Test
    fun `support export prefix`() {
        val file = createEnvFile(
            """
            export DB_HOST=localhost
            export DB_PORT=5432
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals(mapOf("DB_HOST" to "localhost", "DB_PORT" to "5432"), result)
    }

    @Test
    fun `value containing equals sign`() {
        val file = createEnvFile(
            """
            CONNECTION_STRING=host=localhost;port=5432
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("host=localhost;port=5432", result["CONNECTION_STRING"])
    }

    @Test
    fun `throw error for malformed line`() {
        val file = createEnvFile(
            """
            VALID=value
            INVALID_LINE_WITHOUT_EQUALS
            """.trimIndent()
        )

        val exception = assertThrows(CliError::class.java) {
            EnvFileParser.parseEnvFile(file)
        }
        assertTrue(exception.message.contains("Malformed line 2"))
    }

    @Test
    fun `throw error for nonexistent file`() {
        val file = File("/nonexistent/path/.env")

        val exception = assertThrows(CliError::class.java) {
            EnvFileParser.parseEnvFile(file)
        }
        assertTrue(exception.message.contains("does not exist"))
    }

    @Test
    fun `throw error for invalid variable name`() {
        val file = createEnvFile(
            """
            123INVALID=value
            """.trimIndent()
        )

        val exception = assertThrows(CliError::class.java) {
            EnvFileParser.parseEnvFile(file)
        }
        assertTrue(exception.message.contains("Invalid variable name"))
    }

    @Test
    fun `resolveEnv returns env when no file provided`() {
        val env = mapOf("KEY" to "value")
        val result = EnvFileParser.resolveEnv(null, env)
        assertEquals(env, result)
    }

    @Test
    fun `resolveEnv merges file with explicit env`() {
        val file = createEnvFile(
            """
            FROM_FILE=file_value
            SHARED=file_value
            """.trimIndent()
        )
        val env = mapOf("SHARED" to "cli_value", "FROM_CLI" to "cli_value")

        val result = EnvFileParser.resolveEnv(file, env)

        assertEquals("file_value", result["FROM_FILE"])
        assertEquals("cli_value", result["SHARED"]) // -e takes precedence
        assertEquals("cli_value", result["FROM_CLI"])
    }

    @Test
    fun `resolveEnv merges keys from file and env`() {
        val file = createEnvFile(
            """
            API_URL=https://staging.example.com
            DB_HOST=localhost
            """.trimIndent()
        )
        val env = mapOf("API_KEY" to "secret123", "DEBUG" to "true")

        val result = EnvFileParser.resolveEnv(file, env)

        assertEquals(4, result.size)
        assertEquals("https://staging.example.com", result["API_URL"])
        assertEquals("localhost", result["DB_HOST"])
        assertEquals("secret123", result["API_KEY"])
        assertEquals("true", result["DEBUG"])
    }

    @Test
    fun `parse underscore in variable names`() {
        val file = createEnvFile(
            """
            _PRIVATE=hidden
            MY_VAR_2=test
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals(mapOf("_PRIVATE" to "hidden", "MY_VAR_2" to "test"), result)
    }

    @Test
    fun `empty file returns empty map`() {
        val file = createEnvFile("")
        val result = EnvFileParser.parseEnvFile(file)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `file with only comments returns empty map`() {
        val file = createEnvFile(
            """
            # comment 1
            # comment 2
            """.trimIndent()
        )
        val result = EnvFileParser.parseEnvFile(file)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `strip inline comments from unquoted values`() {
        val file = createEnvFile(
            """
            APP_ID=com.example.app # inline comment
            PORT=8080 #no space
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("com.example.app", result["APP_ID"])
        assertEquals("8080", result["PORT"])
    }

    @Test
    fun `preserve hash inside double-quoted values`() {
        val file = createEnvFile(
            """
            COLOR="# red"
            CHANNEL="test #general"
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("# red", result["COLOR"])
        assertEquals("test #general", result["CHANNEL"])
    }

    @Test
    fun `preserve hash inside single-quoted values`() {
        val file = createEnvFile(
            """
            COLOR='# blue'
            CHANNEL='test #random'
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("# blue", result["COLOR"])
        assertEquals("test #random", result["CHANNEL"])
    }

    @Test
    fun `unquoted value with hash but no preceding space is not a comment`() {
        val file = createEnvFile(
            """
            COLOR=#FF0000
            """.trimIndent()
        )

        val result = EnvFileParser.parseEnvFile(file)

        assertEquals("#FF0000", result["COLOR"])
    }
}