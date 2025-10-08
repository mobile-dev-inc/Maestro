package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

class PathResolverTest {

    @TempDir
    lateinit var tempDir: File



    data class AliasTestCase(
        val path: String,
        val expected: String
    )

    @ParameterizedTest
    @MethodSource("aliasTestCases")
    fun `resolveAliases should resolve $path`(testCase: AliasTestCase) {
        val result = PathResolver.resolveAliases(testCase.path, testAliasesConfig)
        assertThat(result).isEqualTo(testCase.expected)
    }

    companion object {
        private val testAliases = mapOf(
            "@proj" to "libs/screens",
            "!proj" to "src/components",
            "~proj/my-package" to "src/components"
        )
        
        private val testAliasesConfig = WorkspaceConfig(paths = testAliases)

        @JvmStatic
        fun aliasTestCases() = listOf(
            AliasTestCase(
                path = "@proj/my-screen",
                expected = "libs/screens/my-screen"
            ),
            AliasTestCase(
                path = "!proj/my-package/my-screen.fil",
                expected = "src/components/my-package/my-screen.fil"
            ),
            AliasTestCase(
                path = "~proj/my-package/my-components/file.yaml",
                expected = "src/components/my-components/file.yaml"
            ),
            AliasTestCase(
                path = "!unknown/my-screen",
                expected = "!unknown/my-screen"
            )
        )

        @JvmStatic
        fun validationTestCases() = listOf(
            ValidationTestCase(
                workspaceConfig = WorkspaceConfig(paths = null),
                expectedErrors = emptyList(),
                description = "should return empty list when no paths configured"
            )
        )
    }

    @Test
    fun `resolveAliases should return original path when no aliases configured`() {
        val path = "@proj/my-screen"
        val workspaceConfig = WorkspaceConfig(paths = null)
        val result = PathResolver.resolveAliases(path, workspaceConfig)
        assertThat(result).isEqualTo(path)
    }

    @Test
    fun `resolveAliases should return original path when no aliases match`() {
        val path = "regular/path"
        val workspaceConfig = WorkspaceConfig(paths = mapOf("proj" to "libs/screens"))
        val result = PathResolver.resolveAliases(path, workspaceConfig)
        assertThat(result).isEqualTo(path)
    }

    data class ValidationTestCase(
        val workspaceConfig: WorkspaceConfig,
        val expectedErrors: List<String>,
        val description: String
    )

    @ParameterizedTest
    @MethodSource("validationTestCases")
    fun `validateAliasPaths should handle various validation scenarios`(testCase: ValidationTestCase) {
        val errors = PathResolver.validateAliasPaths(testCase.workspaceConfig, tempDir)
        assertThat(errors).containsExactlyElementsIn(testCase.expectedErrors)
    }

    @Test
    fun `validateAliasPaths should detect circular references`() {
        val workspaceConfig = WorkspaceConfig(paths = mapOf("@a" to "@b/path", "@b" to "@a/path"))
        val errors = PathResolver.validateAliasPaths(workspaceConfig, tempDir)
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("Circular references are not supported") }).isTrue()
    }

    @Test
    fun `validateAliasPaths should detect non-existent paths`() {
        val workspaceConfig = WorkspaceConfig(paths = mapOf("proj" to "non-existent/path"))
        val errors = PathResolver.validateAliasPaths(workspaceConfig, tempDir)
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("non-existent path") }).isTrue()
    }

    @Test
    fun `validateAliasPaths should pass for valid paths`() {
        val existingDir = File(tempDir, "existing-dir")
        existingDir.mkdirs()
        val workspaceConfig = WorkspaceConfig(paths = mapOf("proj" to "existing-dir"))
        val errors = PathResolver.validateAliasPaths(workspaceConfig, tempDir)
        assertThat(errors).isEmpty()
    }
}
