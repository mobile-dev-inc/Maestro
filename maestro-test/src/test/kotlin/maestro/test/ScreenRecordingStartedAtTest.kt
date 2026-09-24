package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.test.drivers.FakeDriver
import okio.Buffer
import org.junit.jupiter.api.Test
import java.time.Instant

class ScreenRecordingStartedAtTest {

    @Test
    fun `startScreenRecording reports when the recording started`() {
        val driver = FakeDriver().also { it.open() }
        Maestro(driver).use { maestro ->
            val before = Instant.now()
            val recording = runBlocking { maestro.startScreenRecording(Buffer()) }
            val after = Instant.now()

            val startedAt = recording.startedAt
            assertThat(startedAt).isNotNull()
            assertThat(startedAt!!).isAtLeast(before)
            assertThat(startedAt).isAtMost(after)

            recording.close()  // pads to the 3s minimum duration, so this test takes ~3s
        }
    }

    @Test
    fun `a second startScreenRecording while one is running is a no-op with no start time`() {
        val driver = FakeDriver().also { it.open() }
        Maestro(driver).use { maestro ->
            val first = runBlocking { maestro.startScreenRecording(Buffer()) }
            val second = runBlocking { maestro.startScreenRecording(Buffer()) }

            assertThat(second.startedAt).isNull()

            second.close()
            first.close()
        }
    }
}
