package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TakeScreenshotCommand
import maestro.test.drivers.FakeDriver
import maestro.utils.FileAccessScope
import okio.sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * assertScreenshot looks for its baseline in two independent bounded scopes: the workspace
 * (the flow-relative candidate) and the artifacts takeScreenshot dir (a shot captured earlier
 * in the same run). A candidate whose path escapes its own scope is skipped, not thrown out of
 * the search, so an out-of-scope artifacts candidate can never abort a valid workspace candidate,
 * and a path outside both scopes ends as a plain not-found assertion failure.
 */
class AssertScreenshotScopeTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path by lazy { Files.createDirectories(tempDir.resolve("workspace")) }
    private val flows: Path by lazy { Files.createDirectories(workspace.resolve("flows")) }
    // Sibling of the workspace, so it sits outside the workspace scope by construction.
    private val artifactsDir: Path by lazy { Files.createDirectories(tempDir.resolve("artifacts")) }

    private fun orchestra(maestro: Maestro) = Orchestra(
        maestro,
        artifactsDir = artifactsDir,
        lookupTimeoutMs = 0L,
        optionalLookupTimeoutMs = 0L,
        scope = FileAccessScope.under(workspace),
    )

    private fun seedScreenshot(maestro: Maestro, target: Path) {
        Files.createDirectories(target.parent)
        runBlocking { maestro.takeScreenshot(target.toFile().sink(), false) }
    }

    private fun assertScreenshot(maestro: Maestro, path: String) = MaestroCommand(
        assertScreenshotCommand = AssertScreenshotCommand(
            path = path,
            thresholdPercentage = "95",
            flowPath = flows,
        ),
    ).let { command ->
        runBlocking { orchestra(maestro).runFlow(listOf(command)) }
    }

    @Test
    fun `a plain artifacts-relative baseline resolves and asserts without throwing`() {
        FakeDriver().apply { open() }.let { driver ->
            Maestro(driver).use { maestro ->
                runBlocking {
                    orchestra(maestro).runFlow(
                        listOf(
                            MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "baseline")),
                            MaestroCommand(
                                assertScreenshotCommand = AssertScreenshotCommand(
                                    path = "baseline",
                                    thresholdPercentage = "95",
                                    flowPath = flows,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `a parent climb into the workspace resolves via the flow candidate when the artifacts candidate is out of scope`() {
        FakeDriver().apply { open() }.let { driver ->
            Maestro(driver).use { maestro ->
                // "../baseline.png" from the flow dir stays inside the workspace (candidate #1),
                // but the same string escapes the artifacts takeScreenshot dir (candidate #2 → skipped).
                seedScreenshot(maestro, workspace.resolve("baseline.png"))

                assertScreenshot(maestro, "../baseline.png")
            }
        }
    }

    @Test
    fun `a baseline outside both scopes fails as not-found, not a scope violation`() {
        FakeDriver().apply { open() }.let { driver ->
            Maestro(driver).use { maestro ->
                val baseline = tempDir.resolve("outside/baseline.png")
                seedScreenshot(maestro, baseline)

                val error = assertThrows<MaestroException.AssertionFailure> {
                    assertScreenshot(maestro, baseline.toAbsolutePath().toString())
                }

                assertThat(error.message).contains("Screenshot file not found")
            }
        }
    }
}
