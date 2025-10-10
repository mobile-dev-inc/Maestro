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
        fun `resolve should resolve $path`(testCase: AliasTestCase) {
            val result = PathResolver.resolve(testCase.path, testPathAliasesConfig)
            assertThat(result).isEqualTo(testCase.expected)
        }

    companion object {
        private val testPathAliases = mapOf(
            "@proj" to "apps/my-project",
            "!components" to "libs/components",
            "@monorepo/design-system" to "src/packages/design-system"
        )
        
        private val testPathAliasesConfig = WorkspaceConfig(pathAliases = testPathAliases)

        @JvmStatic
        fun aliasTestCases() = listOf(
            AliasTestCase(
                path = "@proj/my-screen",
                expected = "apps/my-project/my-screen"
            ),
            AliasTestCase(
                path = "!components/my-package/my-screen.fil",
                expected = "libs/components/my-package/my-screen.fil"
            ),
            AliasTestCase(
                path = "@monorepo/design-system/my-components/file.yaml",
                expected = "src/packages/design-system/my-components/file.yaml"
            ),
            AliasTestCase(
                path = "!unknown/my-screen",
                expected = "!unknown/my-screen"
            )
        )

            @JvmStatic
            fun validationTestCases() = listOf(
                ValidationTestCase(
                    workspaceConfig = WorkspaceConfig(pathAliases = null),
                    expectedErrors = emptyList(),
                    description = "should return empty list when no pathAliases configured"
                )
            )
    }

    @Test
        fun `resolve should return original path when no pathAliases configured`() {
            val path = "@proj/my-screen"
            val workspaceConfig = WorkspaceConfig(pathAliases = null)
            val result = PathResolver.resolve(path, workspaceConfig)
            assertThat(result).isEqualTo(path)
        }

        @Test
        fun `resolve should return original path when no pathAliases match`() {
            val path = "regular/path"
            val workspaceConfig = WorkspaceConfig(pathAliases = mapOf("proj" to "libs/screens"))
            val result = PathResolver.resolve(path, workspaceConfig)
            assertThat(result).isEqualTo(path)
        }

    data class ValidationTestCase(
        val workspaceConfig: WorkspaceConfig,
        val expectedErrors: List<String>,
        val description: String
    )

    @ParameterizedTest
    @MethodSource("validationTestCases")
        fun `validate should handle various validation scenarios`(testCase: ValidationTestCase) {
            val errors = PathResolver.validate(testCase.workspaceConfig, tempDir)
            assertThat(errors).containsExactlyElementsIn(testCase.expectedErrors)
        }

    @Test
        fun `validate should detect circular references`() {
            val workspaceConfig = WorkspaceConfig(pathAliases = mapOf("@a" to "@b/path", "@b" to "@a/path"))
            val errors = PathResolver.validate(workspaceConfig, tempDir)
            assertThat(errors).isNotEmpty()
            assertThat(errors.any { it.contains("Circular references are not supported") }).isTrue()
        }

        @Test
        fun `validate should detect non-existent paths`() {
            val workspaceConfig = WorkspaceConfig(pathAliases = mapOf("proj" to "non-existent/path"))
            val errors = PathResolver.validate(workspaceConfig, tempDir)
            assertThat(errors).isNotEmpty()
            assertThat(errors.any { it.contains("non-existent path") }).isTrue()
        }

        @Test
        fun `validate should pass for valid paths`() {
            val existingDir = File(tempDir, "existing-dir")
            existingDir.mkdirs()
            val workspaceConfig = WorkspaceConfig(pathAliases = mapOf("proj" to "existing-dir"))
            val errors = PathResolver.validate(workspaceConfig, tempDir)
            assertThat(errors).isEmpty()
        }
}
