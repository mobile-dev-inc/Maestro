package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.orchestra.error.ValidationError
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/**
 * How to add a new error test case:
 *
 * 1. Create a new workspace directory eg. resources/workspaces/e###_test_case_name
 * 2. Run ./gradlew :maestro-orchestra:test --tests "maestro.orchestra.workspace.WorkspaceExecutionPlannerErrorsTest"
 * 3. Error message output will be saved to resources/workspaces/e###_test_case_name/error.actual.txt
 * 4. Move error.actual.txt to error.txt and commit the file
 * 5. Rerun and ensure this passes: ./gradlew :maestro-orchestra:test --tests "maestro.orchestra.workspace.WorkspaceExecutionPlannerErrorsTest"
 *
 *
 * Test case files:
 *
 *   workspace/: The workspace directory passed into WorkspaceExecutionPlanner.plan()
 *   error.txt: The expected error message for the test case
 *   error.actual.txt: The actual error message generated when running the test case (ignored by VCS)
 *   includeTags.txt: Include tags (one per line) to be passed into WorkspaceExecutionPlanner.plan()
 *   excludeTags.txt: Exclude tags (one per line) to be passed into WorkspaceExecutionPlanner.plan()
 *   singleFlow.txt: Indicates that the test should pass the path to the specified flow file instead of the workspace/ directory
 *
 *
 * Note about the "{PROJECT_DIR}" string in error.txt files:
 *
 *   This test fixture replaces any reference to the current project directory with "{PROJECT_DIR}" so that error
 *   messages referencing absolute paths can still be tested on different development machines.
 */
internal class WorkspaceExecutionPlannerErrorsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestCases")
    fun test(testCaseName: String, path: Path) {
        val workspacePath = path.resolve("workspace")
        val singleFlowFilePath = path.resolve("singleFlow.txt").takeIf { it.isRegularFile() }?.readText()
        val expectedErrorPath = path.resolve("error.txt")
        val expectedError = expectedErrorPath.takeIf { it.isRegularFile() }?.readText()
        val includeTags = path.resolve("includeTags.txt").takeIf { it.isRegularFile() }?.readLines() ?: emptyList()
        val excludeTags = path.resolve("excludeTags.txt").takeIf { it.isRegularFile() }?.readLines() ?: emptyList()
        try {
            val inputPath = singleFlowFilePath?.let { workspacePath.resolve(it) } ?: workspacePath
            WorkspaceExecutionPlanner.plan(inputPath, includeTags, excludeTags)
            assertWithMessage("No exception was not thrown. Ensure this test case triggers a ValidationError.").fail()
        } catch (e: Exception) {
            if (e !is ValidationError) {
                e.printStackTrace()
                return assertWithMessage("An exception was thrown but it was not a ValidationError. Ensure this test case triggers a ValidationError. Found: ${e::class.java.name}").fail()
            }

            val actualError = e.message.replace(PROJECT_DIR, "{PROJECT_DIR}")
            val actualErrorPath = path.resolve("error.actual.txt")

            actualErrorPath.writeText(actualError)

            if (expectedError == null) {
                assertWithMessage("Error message written to $actualErrorPath\nIf the error message looks good, copy the file above to error.txt and rerun this test").fail()
            } else if (expectedError != actualError) {
                System.err.println("Expected and actual error messages differ. If actual error message is preferred, move error.actual.txt to error.txt and rerun this test")
                System.err.println("Expected error message path: $expectedErrorPath")
                System.err.println("Actual error message path: $actualErrorPath")
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
