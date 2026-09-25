package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import maestro.android.AndroidDeviceConnection
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [AndroidDriver.startScreenRecording] against a fake adb. The recorder command blocks on
 * [recorderRunning] the way a live `screenrecord` would; the tests release it on teardown
 * instead of calling close(), which pads to a 3s minimum duration.
 */
class AndroidDriverScreenRecordingTest {

    private val recorderRunning = CountDownLatch(1)

    @AfterEach
    fun releaseRecorder() = recorderRunning.countDown()

    private fun reply(exitCode: Int, text: String = ""): AdbShellResponse = mockk {
        every { this@mockk.exitCode } returns exitCode
        every { output } returns text
    }

    private fun connection(recorderExit: () -> AdbShellResponse): AndroidDeviceConnection {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("test -x /data/local/tmp/screenrecord") } returns reply(1)
        every { connection.shell("getprop ro.build.version.sdk") } returns reply(0, "34")
        every { connection.shell(match { it.startsWith("screenrecord ") }) } answers {
            recorderRunning.await(10, TimeUnit.SECONDS)
            recorderExit()
        }
        return connection
    }

    @Test
    fun `startedAt is stamped when the recording file appears on the device`() {
        val connection = connection(recorderExit = { reply(0) })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returnsMany listOf(reply(1), reply(1), reply(0))

        val before = Instant.now()
        val recording = AndroidDriver(connection).startScreenRecording(Buffer())
        val after = Instant.now()

        assertThat(recording.startedAt).isNotNull()
        assertThat(recording.startedAt!!).isAtLeast(before)
        assertThat(recording.startedAt!!).isAtMost(after)
        // A stale file from a previous recording would make the probe fire immediately, so it is removed first.
        verifyOrder {
            connection.shell("rm -f /sdcard/maestro-screenrecording.mp4")
            connection.shell(match { it.startsWith("screenrecord ") })
        }
    }

    @Test
    fun `startedAt is null when the recorder exits before the file appears`() {
        val connection = connection(recorderExit = { reply(1, "screenrecord: unsupported") })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returns reply(1)
        recorderRunning.countDown() // the recorder fails straight away

        val recording = AndroidDriver(connection).startScreenRecording(Buffer())

        assertThat(recording.startedAt).isNull()
    }
}
