package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import maestro.cli.graphics.LocalVideoRenderer.Companion.outputDurationMs
import maestro.cli.runner.resultview.Frame
import org.junit.jupiter.api.Test

class OutputDurationTest {

    private fun textsAt(vararg atMs: Long) = atMs.map { Frame(it, "") }

    @Test
    fun `keeps the recording's length when the terminal's last output is well within it`() {
        assertThat(outputDurationMs(recordingMs = 30_000, lastScreenMs = 29_000, textsAt(1_000, 20_000), recordedForMs = 31_000)).isEqualTo(30_000)
    }

    @Test
    fun `lengthens the video until the terminal's last output has been on show for two seconds`() {
        assertThat(outputDurationMs(recordingMs = 30_000, lastScreenMs = 29_000, textsAt(1_000, 39_000), recordedForMs = 40_000)).isEqualTo(41_000)
    }

    @Test
    fun `keeps the recording's length without terminal output`() {
        assertThat(outputDurationMs(recordingMs = 30_000, lastScreenMs = 29_000, emptyList(), recordedForMs = 40_000)).isEqualTo(30_000)
    }

    @Test
    fun `stops at 3 minutes when the recorder may have hit its time limit`() {
        // As on an API 33 emulator: recording for 4 minutes left a 167.7s file, ending at the last change.
        assertThat(outputDurationMs(recordingMs = 167_700, lastScreenMs = 165_700, textsAt(1_000, 239_000), recordedForMs = 240_000)).isEqualTo(180_000)
    }

    @Test
    fun `stops at the recording's end when a recording stopped at 3 minutes runs past them`() {
        // As on an API 33 emulator: the last frame starts at 178.3s and is given the length of the one before,
        // so the 3-minute recording is 182.9s long.
        assertThat(outputDurationMs(recordingMs = 182_857, lastScreenMs = 178_302, textsAt(1_000, 205_200), recordedForMs = 205_000)).isEqualTo(182_857)
    }

    @Test
    fun `lengthens up to 3 minutes when the host recorded for longer`() {
        assertThat(outputDurationMs(recordingMs = 150_000, lastScreenMs = 149_000, textsAt(1_000, 160_000), recordedForMs = 200_000)).isEqualTo(162_000)
    }

    @Test
    fun `lengthens past 3 minutes when the recording itself is longer`() {
        // Only a device without the time limit records past 3 minutes.
        assertThat(outputDurationMs(recordingMs = 200_000, lastScreenMs = 199_000, textsAt(1_000, 239_000), recordedForMs = 240_000)).isEqualTo(241_000)
    }

    @Test
    fun `lengthens past 3 minutes when the host stopped within them`() {
        assertThat(outputDurationMs(recordingMs = 150_000, lastScreenMs = 149_000, textsAt(1_000, 179_500), recordedForMs = 179_900)).isEqualTo(181_500)
    }

    @Test
    fun `lengthens normally when how long the host recorded is unknown`() {
        assertThat(outputDurationMs(recordingMs = 167_700, lastScreenMs = 165_700, textsAt(1_000, 239_000), recordedForMs = null)).isEqualTo(241_000)
    }
}
