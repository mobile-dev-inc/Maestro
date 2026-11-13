package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class FrameRecorderTest {

    private fun FrameRecorder.screens() =
        getFrames(startedAt = Instant.EPOCH).map { String(Base64.getDecoder().decode(it.content), Charsets.UTF_8) }

    @Test
    fun `is empty until something is recorded`() {
        assertThat(FrameRecorder().getFrames(startedAt = Instant.EPOCH)).isEmpty()
    }

    @Test
    fun `keeps the first screen even when it is empty`() {
        val frameRecorder = FrameRecorder()

        frameRecorder.record("")

        assertThat(frameRecorder.screens()).containsExactly("")
    }

    @Test
    fun `records every screen in order, including repeats`() {
        val frameRecorder = FrameRecorder()

        frameRecorder.record("a")
        frameRecorder.record("a")
        frameRecorder.record("b")

        assertThat(frameRecorder.screens()).containsExactly("a", "a", "b").inOrder()
    }

    @Test
    fun `times frames from when the screen recording started`() {
        var now = Instant.ofEpochMilli(10_000)
        val frameRecorder = FrameRecorder(now = { now })

        frameRecorder.record("before the recording started")
        now = Instant.ofEpochMilli(13_500)
        frameRecorder.record("after")

        val frames = frameRecorder.getFrames(startedAt = Instant.ofEpochMilli(12_000))
        // A screen shown before the recording started is on show from its first frame.
        assertThat(frames.map { it.timestamp }).containsExactly(0L, 1_500L).inOrder()
    }
}
