package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class FrameRecorderTest {

    private fun FrameRecorder.screens() = getFrames().map { String(Base64.getDecoder().decode(it.content), Charsets.UTF_8) }

    @Test
    fun `is empty until something is recorded`() {
        assertThat(FrameRecorder().getFrames()).isEmpty()
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
    fun `timestamps frames from when recording started`() {
        val before = System.currentTimeMillis()
        val frameRecorder = FrameRecorder()

        frameRecorder.record("a")

        val elapsed = System.currentTimeMillis() - before
        assertThat(frameRecorder.getFrames().single().timestamp).isIn(0L..elapsed)
    }
}
