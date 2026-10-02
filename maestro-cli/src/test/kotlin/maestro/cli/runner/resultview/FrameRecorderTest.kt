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
    fun `stores a repeated screen once`() {
        val frameRecorder = FrameRecorder()

        frameRecorder.record("tapOn")
        frameRecorder.record("tapOn")
        frameRecorder.record("tapOn ✅")

        assertThat(frameRecorder.screens()).containsExactly("tapOn", "tapOn ✅").inOrder()
    }

    @Test
    fun `records a screen again when it comes back after a change`() {
        val frameRecorder = FrameRecorder()

        frameRecorder.record("a")
        frameRecorder.record("b")
        frameRecorder.record("a")

        assertThat(frameRecorder.screens()).containsExactly("a", "b", "a").inOrder()
    }

    @Test
    fun `copies a buffer the view keeps writing to`() {
        val frameRecorder = FrameRecorder()
        val buffer = StringBuilder("tapOn")

        frameRecorder.record(buffer)
        frameRecorder.record(buffer)
        buffer.append(" ✅")
        frameRecorder.record(buffer)

        assertThat(frameRecorder.screens()).containsExactly("tapOn", "tapOn ✅").inOrder()
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
