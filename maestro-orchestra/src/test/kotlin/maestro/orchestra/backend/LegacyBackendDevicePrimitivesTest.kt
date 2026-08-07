package maestro.orchestra.backend

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.Maestro
import maestro.ScreenRecording
import okio.Buffer
import org.junit.jupiter.api.Test

/**
 * Seam test for the device primitives added in Task 1.9 (the Phase 1 capstone). Each primitive
 * delegates VERBATIM to the matching `maestro.*` call so the legacy backend stays behaviorally
 * identical to Orchestra's old direct calls:
 *  - takeScreenshot(sink, compressed, bounds?) -> maestro.takeScreenshot(sink, compressed, bounds)
 *  - startScreenRecording(sink) -> maestro.startScreenRecording(sink)
 *  - setAndroidChromeDevToolsEnabled(enabled) -> maestro.setAndroidChromeDevToolsEnabled(enabled)
 */
class LegacyBackendDevicePrimitivesTest {

    @Test
    fun `takeScreenshot delegates to maestro takeScreenshot with no bounds`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)
        val sink = Buffer()

        runBlocking { backend.takeScreenshot(sink, compressed = false) }

        coVerify { fakeMaestro.takeScreenshot(sink, false, null) }
    }

    @Test
    fun `takeScreenshot delegates to maestro takeScreenshot with bounds`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)
        val sink = Buffer()
        val bounds = Bounds(x = 1, y = 2, width = 3, height = 4)

        runBlocking { backend.takeScreenshot(sink, compressed = true, bounds = bounds) }

        coVerify { fakeMaestro.takeScreenshot(sink, true, bounds) }
    }

    @Test
    fun `startScreenRecording delegates to maestro startScreenRecording and returns the handle`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val recording: ScreenRecording = mockk(relaxed = true)
        val sink = Buffer()
        coEvery { fakeMaestro.startScreenRecording(sink) } returns recording

        val backend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking { backend.startScreenRecording(sink) }

        assert(result === recording)
        coVerify { fakeMaestro.startScreenRecording(sink) }
    }

    @Test
    fun `setAndroidChromeDevToolsEnabled delegates to maestro`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)

        backend.setAndroidChromeDevToolsEnabled(true)

        coVerify { fakeMaestro.setAndroidChromeDevToolsEnabled(true) }
    }
}
