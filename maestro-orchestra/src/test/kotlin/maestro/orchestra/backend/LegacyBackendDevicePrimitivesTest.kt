package maestro.orchestra.backend

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.Maestro
import maestro.ScreenRecording
import maestro.orchestra.MaestroConfig
import okio.Buffer
import org.junit.jupiter.api.Test

/**
 * Seam test for the device primitives added in Task 1.9 (the Phase 1 capstone). Each primitive
 * delegates VERBATIM to the matching `maestro.*` call so the legacy backend stays behaviorally
 * identical to Orchestra's old direct calls:
 *  - takeScreenshot(sink, compressed, bounds?) -> maestro.takeScreenshot(sink, compressed, bounds)
 *  - startScreenRecording(sink) -> maestro.startScreenRecording(sink)
 *  - open(appId, config) applies the Android Chrome DevTools toggle folded in from Task 4.0's
 *    former setAndroidChromeDevToolsEnabled seam method:
 *    config.ext["androidWebViewHierarchy"] == "devtools" -> maestro.setAndroidChromeDevToolsEnabled(true/false)
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
    fun `open enables Android Chrome DevTools when config requests the devtools webview hierarchy`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)

        backend.open(
            appId = "com.example.app",
            config = MaestroConfig(ext = mapOf("androidWebViewHierarchy" to "devtools")),
        )

        coVerify { fakeMaestro.setAndroidChromeDevToolsEnabled(true) }
    }

    @Test
    fun `open disables Android Chrome DevTools when config does not request devtools`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)

        backend.open(appId = "com.example.app", config = MaestroConfig())

        coVerify { fakeMaestro.setAndroidChromeDevToolsEnabled(false) }
    }

    @Test
    fun `open does not touch Android Chrome DevTools when there is no config`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val backend = LegacyExecutionBackend(fakeMaestro)

        backend.open(appId = "com.example.app", config = null)

        coVerify(exactly = 0) { fakeMaestro.setAndroidChromeDevToolsEnabled(any()) }
    }
}
