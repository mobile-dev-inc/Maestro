package maestro.cli.util

import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import com.google.common.truth.Truth.assertThat

class WorkingDirectoryTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        WorkingDirectory.baseDir = tempDir
        WorkingDirectory.workspaceConfig = null
    }

    data class ResolveTestCase(
        val inputPath: String,
        val expectedPath: String
    )

    companion object {
        private val testPathAliases = mapOf(
            "@proj" to "libs/screens",
            "@libs" to "src/components"
        )
        
        private val testWorkspaceConfig = WorkspaceConfig(pathAliases = testPathAliases)

        @JvmStatic
        fun resolveTestCases() = listOf(
            ResolveTestCase(
                inputPath = "@proj/my-screen.yaml",
                expectedPath = "libs/screens/my-screen.yaml"
            ),
            ResolveTestCase(
                inputPath = "@unknown/my-screen.yaml",
                expectedPath = "@unknown/my-screen.yaml"
            ),
            ResolveTestCase(
                inputPath = "regular/path/file.yaml",
                expectedPath = "regular/path/file.yaml"
            ),
            ResolveTestCase(
                inputPath = "@libs/components/button.yaml",
                expectedPath = "src/components/components/button.yaml"
            ),
            ResolveTestCase(
                inputPath = "@proj/",
                expectedPath = "libs/screens/"
            )
        )
    }

    @ParameterizedTest
    @MethodSource("resolveTestCases")
    fun `resolve should resolve $inputPath`(testCase: ResolveTestCase) {
        WorkingDirectory.workspaceConfig = testWorkspaceConfig
        val result = WorkingDirectory.resolve(testCase.inputPath)
        val expectedFile = File(tempDir, testCase.expectedPath)
        assertThat(result).isEqualTo(expectedFile)
    }

    @Test
    fun `resolve should work without workspace config`() {
        val path = "some/path/file.yaml"
        WorkingDirectory.workspaceConfig = null
        val result = WorkingDirectory.resolve(path)
        assertThat(result).isEqualTo(File(tempDir, path))
    }

    @Test
        fun `resolve should work with workspace config but no pathAliases`() {
            val path = "some/path/file.yaml"
            WorkingDirectory.workspaceConfig = WorkspaceConfig(pathAliases = null)
            val result = WorkingDirectory.resolve(path)
            assertThat(result).isEqualTo(File(tempDir, path))
        }

    @Test
    fun `resolve should handle absolute paths`() {
        val path = "/absolute/path/file.yaml"
        WorkingDirectory.workspaceConfig = testWorkspaceConfig
        val result = WorkingDirectory.resolve(path)
        assertThat(result).isEqualTo(File(path))
    }

    @Test
    fun `resolve should handle File objects`() {
        val file = File("some/path/file.yaml")
        WorkingDirectory.workspaceConfig = testWorkspaceConfig
        val result = WorkingDirectory.resolve(file)
        assertThat(result).isEqualTo(File(tempDir, "some/path/file.yaml"))
    }
}
