package util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class LocalSimulatorUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    private val simulatorUtils = LocalSimulatorUtils(tempFileHandler = mockk())

    @BeforeEach
    fun setUp() = mockkObject(CommandLineUtils)

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `launchSimulator skips opening Simulator app when it is missing`() {
        // Xcode 27 ships without Simulator.app
        val missingApp = File(tempDir.toFile(), "Simulator.app")

        simulatorUtils.launchSimulator("some-udid", simulatorApp = missingApp)

        verify(exactly = 0) { CommandLineUtils.runCommand(any(), any(), any(), any()) }
    }

    @Test
    fun `launchSimulator opens Simulator app when it exists`() {
        val simulatorApp = File(tempDir.toFile(), "Simulator.app").apply { mkdirs() }
        val command = slot<List<String>>()
        every { CommandLineUtils.runCommand(capture(command), any(), any(), any()) } returns mockk()

        simulatorUtils.launchSimulator("some-udid", simulatorApp = simulatorApp)

        assertThat(command.captured).containsExactly(
            "open", "-a", simulatorApp.path, "--args", "-CurrentDeviceUDID", "some-udid"
        ).inOrder()
    }
}
