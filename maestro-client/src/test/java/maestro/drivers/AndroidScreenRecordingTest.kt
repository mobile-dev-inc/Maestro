package maestro.drivers

import com.google.common.truth.Truth.assertThat
import maestro.android.AndroidDeviceConnection
import okio.sink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * End-to-end proof that [AndroidDriver.startScreenRecording] on API < 34 stitches multiple
 * `screenrecord` segments into a recording that outlasts the 180 s single-recording cap. Drives the
 * real driver with 5 s segments for ~15 s, then asserts the result is far longer than one segment,
 * decodes cleanly under **ffmpeg**, and loses no frames on re-encode. Skipped without an API < 34
 * emulator + ffmpeg.
 */
internal class AndroidScreenRecordingTest {

    private val device = AdbTestDevice()

    @Test
    internal fun `segmented recording stitches past a single segment length`(@TempDir tempDir: Path) {
        assumeTrue(device.isReachable(), "No emulator reachable via adb; skipping")
        assumeTrue(Ffmpeg.available(), "ffmpeg/ffprobe not found; skipping")
        val apiLevel = device.apiLevel()
        assumeTrue(apiLevel < 34, "This path only applies to API < 34; device is API $apiLevel")

        val segmentSeconds = 5
        val recordSeconds = 15
        val outFile = tempDir.resolve("e2e-recording.mp4").toFile()

        val connection = AndroidDeviceConnection.byId(device.serial, "localhost")
            ?: error("Could not connect to ${device.serial}")
        try {
            device.openScrollableScreen()
            val recording = AndroidDriver(connection, emulatorName = device.serial)
                .startScreenRecording(outFile.sink(), segmentSeconds)
            device.scrollFor(recordSeconds * 1000L) // rolls over ~3 five-second segments
            recording.close()
        } finally {
            connection.close()
        }
        assertThat(outFile.length()).isGreaterThan(0L)

        // Decodes cleanly (no mangled NAL framing at the seams) and loses no frames on re-encode.
        assertThat(Ffmpeg.decodeErrors(outFile)).isEmpty()
        val frames = Ffmpeg.decodedFrameCount(outFile)
        assertThat(Ffmpeg.reencodedFrameCount(outFile, tempDir)).isEqualTo(frames)

        // Far longer than one 5 s segment — only concatenation of ~3 segments reaches this.
        val duration = Ffmpeg.durationSeconds(outFile)
        println("E2E segmented recording: frames=$frames duration=%.3fs".format(duration))
        assertThat(duration).isGreaterThan(segmentSeconds + 2.0)
        assertThat(duration).isLessThan(recordSeconds + 6.0)
    }
}
