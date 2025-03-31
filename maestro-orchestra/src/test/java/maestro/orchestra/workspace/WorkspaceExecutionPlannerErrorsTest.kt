package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.orchestra.error.ValidationError
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/**
 * How to add a new error test case:
 *
 * 1. Create a new workspace directory eg. resources/workspaces/e###_test_case_name
 * 2. Run GENERATE_ERRORS=true ./gradlew :maestro-orchestra:test --tests "maestro.orchestra.workspace.WorkspaceExecutionPlannerErrorsTest"
 * 3. Error messages for all test cases will be regenerated and saved to resources/workspaces/e###_test_case_name/error.txt
 * 4. Manually validate that the generated error messages are correct.
 * 5. Run the tests without the GENERATE_ERRORS env var and ensure this passes: ./gradlew :maestro-orchestra:test --tests "maestro.orchestra.workspace.WorkspaceExecutionPlannerErrorsTest"
 * 6. Commit your changes.
 *
 *
 * Test case files:
 *
 *   workspace/: The workspace directory passed into WorkspaceExecutionPlanner.plan()
 *   error.txt: The expected error message for the test case
 *   includeTags.txt: Include tags (one per line) to be passed into WorkspaceExecutionPlanner.plan()
 *   excludeTags.txt: Exclude tags (one per line) to be passed into WorkspaceExecutionPlanner.plan()
 *   singleFlow.txt: Indicates that the test should pass the path to the specified flow file instead of the workspace/ directory
 *
 */
internal class WorkspaceExecutionPlannerErrorsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestCases")
    fun test(testCaseName: String, originalPath: Path) {
        val workspace = File("/tmp/WorkspaceExecutionPlannerErrorsTest_workspace").apply { deleteRecursively() }
        originalPath.toFile().copyRecursively(workspace)
        val path = workspace.toPath()

        val workspacePath = path.resolve("workspace")
        val singleFlowFilePath = path.resolve("singleFlow.txt").takeIf { it.isRegularFile() }?.readText()
        val expectedErrorPath = path.resolve("error.txt")
        val expectedError = expectedErrorPath.takeIf { it.isRegularFile() }?.readText()
        val includeTags = path.resolve("includeTags.txt").takeIf { it.isRegularFile() }?.readLines() ?: emptyList()
        val excludeTags = path.resolve("excludeTags.txt").takeIf { it.isRegularFile() }?.readLines() ?: emptyList()
        try {
            val inputPath = singleFlowFilePath?.let { workspacePath.resolve(it) } ?: workspacePath
            WorkspaceExecutionPlanner.plan(
                input = setOf(inputPath),
                includeTags = includeTags,
                excludeTags = excludeTags,
                config = null,
            )
            assertWithMessage("No exception was not thrown. Ensure this test case triggers a ValidationError.").fail()
        } catch (e: Exception) {
            if (e !is ValidationError) {
                e.printStackTrace()
                return assertWithMessage("An exception was thrown but it was not a ValidationError. Ensure this test case triggers a ValidationError. Found: ${e::class.java.name}").fail()
            }

            val actualError = e.message

            if (System.getenv("GENERATE_ERRORS") == "true") {
                originalPath.resolve("error.txt").writeText(actualError)
            } else if (expectedError != actualError) {
                System.err.println("Expected and actual error messages differ. If actual error message is preferred, rerun this test with GENERATE_ERRORS=true")
                assertThat(actualError).isEqualTo(expectedError)
            }
        }
    }

    companion object {

        private val PROJECT_DIR = System.getenv("PROJECT_DIR")?.let { Paths.get(it).absolutePathString().trimEnd('/') } ?: throw RuntimeException("Enable to determine project directory")

        @JvmStatic
        private fun provideTestCases(): List<Arguments> {
            return Paths.get(PROJECT_DIR)
                .resolve("src/test/resources/workspaces")
                .listDirectoryEntries()
                .filter { it.isDirectory() && it.name.startsWith("e") }
                .map { Arguments.of(it.name, it) }
        }
    }
}
