package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.ScreenRecording
import maestro.test.drivers.FakeDriver
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Recordings are deliberately not closed here: close() pads to a 3s minimum duration and the
 * FakeDriver has nothing to release.
 */
class ScreenRecordingStartedAtTest {

    @Test
    fun `falls back to the host clock when the driver reports no start time`() {
        val driver = FakeDriver().also { it.open() }
        Maestro(driver).use { maestro ->
            val before = Instant.now()
            val recording = runBlocking { maestro.startScreenRecording(Buffer()) }
            val after = Instant.now()

            assertThat(recording.startedAt).isNotNull()
            assertThat(recording.startedAt!!).isAtLeast(before)
            assertThat(recording.startedAt!!).isAtMost(after)
        }
    }

    @Test
    fun `a driver that knows when its recording started wins over the host clock`() {
        val driverStartedAt = Instant.ofEpochMilli(1_700_000_000_000L)
        val driver = object : FakeDriver() {
            override fun startScreenRecording(out: Sink): ScreenRecording {
                val inner = super.startScreenRecording(out)
                return object : ScreenRecording by inner {
                    override val startedAt: Instant = driverStartedAt
                }
            }
        }.also { it.open() }

        Maestro(driver).use { maestro ->
            val recording = runBlocking { maestro.startScreenRecording(Buffer()) }

            assertThat(recording.startedAt).isEqualTo(driverStartedAt)
        }
    }

    @Test
    fun `a second startScreenRecording while one is running is a no-op with no start time`() {
        val driver = FakeDriver().also { it.open() }
        Maestro(driver).use { maestro ->
            runBlocking { maestro.startScreenRecording(Buffer()) }
            val second = runBlocking { maestro.startScreenRecording(Buffer()) }

            assertThat(second.startedAt).isNull()
        }
    }

    @Test
    fun `a driver failure to start leaves the next recording free to start`() {
        var fail = true
        val driver = object : FakeDriver() {
            override fun startScreenRecording(out: Sink): ScreenRecording {
                if (fail) throw IllegalStateException("emulator cannot record")
                return super.startScreenRecording(out)
            }
        }.also { it.open() }

        Maestro(driver).use { maestro ->
            assertThrows<IllegalStateException> { runBlocking { maestro.startScreenRecording(Buffer()) } }
            fail = false

            val recording = runBlocking { maestro.startScreenRecording(Buffer()) }

            assertThat(recording.startedAt).isNotNull() // a real recording, not the in-progress no-op
        }
    }
}
