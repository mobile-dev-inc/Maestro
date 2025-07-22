package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.FileUtils.isWebFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FileUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isWebFlow should return true for simple HTTP URL`() {
        // Given
        val yamlContent = """
            appId: http://example.com
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `isWebFlow should return true for simple HTTPS URL`() {
        // Given
        val yamlContent = """
            appId: https://example.com
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `isWebFlow should return false for mobile app package`() {
        // Given
        val yamlContent = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should return true for URL with path`() {
        // Given
        val yamlContent = """
            appId: https://example.com/app/login
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `isWebFlow should return true for JavaScript expression evaluating to URL`() {
        // Given
        val yamlContent = """
            appId: ${output.webUrl || "https://example.com"}
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `isWebFlow should return false for JavaScript expression evaluating to package name`() {
        // Given
        val yamlContent = """
            appId: ${output.appId || "com.example.app"}
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should handle JavaScript evaluation failure gracefully`() {
        // Given
        val yamlContent = """
            appId: ${'$'}{invalid.syntax.error}
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        // Should fall back to original appId and return false since it doesn't match URL pattern
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should return false for non-existent file`() {
        // Given
        val nonExistentFile = tempDir.resolve("nonexistent.yaml").toFile()

        // When
        val result = nonExistentFile.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should return false for directory`() {
        // Given
        val directory = tempDir.toFile()

        // When
        val result = directory.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should return false for config files`() {
        // Given
        val configYaml = tempDir.resolve("config.yaml").toFile()
        configYaml.writeText("""
            appId: https://example.com
            ---
            - launchApp
        """.trimIndent())

        // When
        val result = configYaml.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should return false for non-YAML files`() {
        // Given
        val txtFile = tempDir.resolve("test.txt").toFile()
        txtFile.writeText("appId: https://example.com")

        // When
        val result = txtFile.isWebFlow()

        // Then
        assertThat(result).isFalse()
    }

    @Test
    fun `isWebFlow should handle environment variable substitution`() {
        // Given
        val originalValue = System.getProperty("MAESTRO_TEST_URL")
        try {
            System.setProperty("MAESTRO_TEST_URL", "https://test-env.com")
            
            val yamlContent = """
                appId: ${'$'}{process.env.MAESTRO_TEST_URL || "com.example.app"}
                ---
                - launchApp
            """.trimIndent()
            val yamlFile = tempDir.resolve("test.yaml").toFile()
            yamlFile.writeText(yamlContent)

            // When
            val result = yamlFile.isWebFlow()

            // Then
            assertThat(result).isTrue()
        } finally {
            if (originalValue != null) {
                System.setProperty("MAESTRO_TEST_URL", originalValue)
            } else {
                System.clearProperty("MAESTRO_TEST_URL")
            }
        }
    }

    @Test
    fun `isWebFlow should return true for localhost URLs`() {
        // Given
        val yamlContent = """
            appId: http://localhost:3000
            ---
            - launchApp
        """.trimIndent()
        val yamlFile = tempDir.resolve("test.yaml").toFile()
        yamlFile.writeText(yamlContent)

        // When
        val result = yamlFile.isWebFlow()

        // Then
        assertThat(result).isTrue()
    }
}