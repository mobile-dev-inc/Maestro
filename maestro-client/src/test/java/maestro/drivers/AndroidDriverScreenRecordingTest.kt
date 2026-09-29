package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import maestro.android.AndroidDeviceConnection
import maestro.android.AndroidOperationFailedException
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AndroidDriver.startScreenRecording] against a fake adb. The recorder command blocks on
 * [recorderRunning] the way a live `screenrecord` would; the tests release it on teardown
 * instead of calling close(), which pads to a 3s minimum duration.
 */
class AndroidDriverScreenRecordingTest {

    private val recorderRunning = CountDownLatch(1)

    /** The thread the recorder command ran on, to check it does not outlive the recording. */
    @Volatile
    private var recorderThread: Thread? = null

    @AfterEach
    fun releaseRecorder() = recorderRunning.countDown()

    private fun reply(exitCode: Int, text: String = ""): AdbShellResponse = mockk(relaxed = true) {
        every { this@mockk.exitCode } returns exitCode
        every { output } returns text
        every { errorOutput } returns text
        every { allOutput } returns text
    }

    private fun connection(recorderExit: () -> AdbShellResponse): AndroidDeviceConnection {
        val connection = mockk<AndroidDeviceConnection>(relaxed = true)
        every { connection.shell("test -x /data/local/tmp/screenrecord") } returns reply(1)
        every { connection.shell("getprop ro.build.version.sdk") } returns reply(0, "34")
        every { connection.shell(match { it.startsWith("screenrecord ") }) } answers {
            recorderThread = Thread.currentThread()
            recorderRunning.await(10, TimeUnit.SECONDS)
            recorderExit()
        }
        // SIGINT ends a live screenrecord.
        every { connection.shell("killall -INT screenrecord screenrecord-bin") } answers {
            recorderRunning.countDown()
            reply(0)
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

        assertThat(recording.startedAt).isAtLeast(before)
        assertThat(recording.startedAt).isAtMost(after)
        // A stale file from a previous recording would make the probe fire immediately, so it is removed first.
        verifyOrder {
            connection.shell("rm -f /sdcard/maestro-screenrecording.mp4")
            connection.shell(match { it.startsWith("screenrecord ") })
        }
    }

    @Test
    fun `the recorder's failure surfaces at start when it exits before the file appears`() {
        val connection = connection(recorderExit = { reply(1, "screenrecord: unsupported") })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returns reply(1)
        recorderRunning.countDown() // the recorder fails straight away

        val failure = assertThrows<AndroidOperationFailedException> {
            AndroidDriver(connection).startScreenRecording(Buffer())
        }

        assertThat(failure).hasMessageThat().contains("Failed to capture screen recording")
    }

    @Test
    fun `the recorder is stopped when the start probe itself fails`() {
        val connection = connection(recorderExit = { reply(0) })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } throws IllegalStateException("adb went away")

        assertThrows<IllegalStateException> {
            AndroidDriver(connection).startScreenRecording(Buffer())
        }

        // Otherwise screenrecord keeps running on the device with nothing left to stop it.
        verify { connection.shell("killall -INT screenrecord screenrecord-bin") }
    }

    @Test
    fun `start fails and the recorder is stopped when the file never appears within the bound`() {
        val connection = connection(recorderExit = { reply(0) })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returns reply(1)
        val driver = AndroidDriver(connection, screenRecordingStartTimeoutMs = 300)

        val failure = assertThrows<AndroidOperationFailedException> {
            driver.startScreenRecording(Buffer())
        }

        assertThat(failure).hasMessageThat().contains("did not start within 300ms")
        verify { connection.shell("killall -INT screenrecord screenrecord-bin") }
    }

    @Test
    fun `a failed start returns only after the stopped recorder has exited`() {
        val recorderExited = AtomicBoolean(false)
        val connection = connection(recorderExit = {
            Thread.sleep(200) // screenrecord flushing its file after SIGINT
            recorderExited.set(true)
            reply(0)
        })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returns reply(1)

        assertThrows<AndroidOperationFailedException> {
            AndroidDriver(connection, screenRecordingStartTimeoutMs = 300).startScreenRecording(Buffer())
        }

        // Otherwise the next start's rm -f and new recorder race the old one on the same file.
        assertThat(recorderExited.get()).isTrue()
    }

    @Test
    fun `the recorder's thread ends with the recorder`() {
        val connection = connection(recorderExit = { reply(0) })
        every { connection.shell("test -e /sdcard/maestro-screenrecording.mp4") } returns reply(1)

        assertThrows<AndroidOperationFailedException> {
            AndroidDriver(connection, screenRecordingStartTimeoutMs = 300).startScreenRecording(Buffer())
        }

        val thread = checkNotNull(recorderThread)
        thread.join(2_000)
        assertThat(thread.isAlive).isFalse()
    }
}
