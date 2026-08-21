package maestro.orchestra.devicecore

import maestro.ScreenRecording
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.ElementSelector
import okio.Buffer
import okio.Sink
import java.io.File

/**
 * A gateway-level fake: overrides only the verbs a test exercises (the built verbs plus the
 * canned-bytes reads [ArtifactsGenerator] uses) and inherits the interface's throwing
 * NotImplemented default for every roadmap verb. No mocks.
 */
class FakeDeviceGateway(
    private val screenshotBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
    private val deviceLogs: List<CapturedDeviceArtifact> = emptyList(),
    private val crashArtifacts: List<CapturedDeviceArtifact> = emptyList(),
    private val onAssert: (ElementSelector, AssertMode) -> Unit = { _, _ -> },
) : DeviceGateway {
    val launched = mutableListOf<String>()
    val tapped = mutableListOf<ElementSelector>()
    val asserted = mutableListOf<Pair<ElementSelector, AssertMode>>()
    var recordingStarted = false
    var deviceLogCaptureStarted = false
    var closed = false

    override fun connect(target: DeviceCoreTarget, appId: String?) {}
    override fun close() { closed = true }
    override fun launchApp(appId: String) { launched += appId }

    override fun tap(selector: ElementSelector): ChosenElement? {
        tapped += selector
        return null
    }

    override fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement? {
        asserted += selector to mode
        onAssert(selector, mode)
        return null
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector?) {
        val buffer = Buffer().write(screenshotBytes)
        out.write(buffer, buffer.size)
        out.flush()
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        recordingStarted = true
        return object : ScreenRecording {
            override fun close() { /* no-op */ }
        }
    }

    override fun startDeviceLogCapture() { deviceLogCaptureStarted = true }
    override fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> = deviceLogs
    override fun collectCrashArtifacts(appId: String?, flowStartMs: Long, outputDir: File): List<CapturedDeviceArtifact> =
        crashArtifacts
}
