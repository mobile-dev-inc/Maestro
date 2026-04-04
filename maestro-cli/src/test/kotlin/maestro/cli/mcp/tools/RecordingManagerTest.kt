package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.LocalSimulatorUtils
import java.io.File

class RecordingManagerTest {

    private lateinit var localSimulatorUtils: LocalSimulatorUtils
    private lateinit var recordingManager: RecordingManager

    private val fakeProcess = mockk<Process>(relaxed = true) {
        every { isAlive } returns true
    }
    private val fakeVideoFile = File.createTempFile("test-recording", ".mov").also {
        it.writeText("fake video")
        it.deleteOnExit()
    }
    private val fakeScreenRecording = LocalSimulatorUtils.ScreenRecording(fakeProcess, fakeVideoFile)

    @BeforeEach
    fun setUp() {
        localSimulatorUtils = mockk()
        every { localSimulatorUtils.startScreenRecording(any()) } returns fakeScreenRecording
        every { localSimulatorUtils.stopScreenRecording(any()) } returns fakeVideoFile

        recordingManager = RecordingManager(localSimulatorUtils)
    }

    @Test
    fun `startRecording returns state with recording ID`() {
        val state = recordingManager.startRecording("device-1", null)

        assertThat(state.recordingId).isNotEmpty()
        assertThat(state.deviceId).isEqualTo("device-1")
        verify { localSimulatorUtils.startScreenRecording("device-1") }
    }

    @Test
    fun `startRecording throws if recording already active for device`() {
        recordingManager.startRecording("device-1", null)

        val exception = assertThrows<IllegalStateException> {
            recordingManager.startRecording("device-1", null)
        }
        assertThat(exception.message).contains("already active")
    }

    @Test
    fun `startRecording allows different devices concurrently`() {
        val state1 = recordingManager.startRecording("device-1", null)
        val state2 = recordingManager.startRecording("device-2", null)

        assertThat(state1.recordingId).isNotEqualTo(state2.recordingId)
    }

    @Test
    fun `stopRecording throws if no recording active`() {
        val exception = assertThrows<IllegalStateException> {
            recordingManager.stopRecording("device-1", "some-id")
        }
        assertThat(exception.message).contains("No active recording")
    }

    @Test
    fun `stopRecording throws on recording ID mismatch`() {
        val state = recordingManager.startRecording("device-1", null)

        val exception = assertThrows<IllegalArgumentException> {
            recordingManager.stopRecording("device-1", "wrong-id")
        }
        assertThat(exception.message).contains("mismatch")
        assertThat(exception.message).contains(state.recordingId)
    }

    @Test
    fun `stopRecording returns video path`() {
        val state = recordingManager.startRecording("device-1", null)

        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.videoPath).isEqualTo(fakeVideoFile.absolutePath)
        verify { localSimulatorUtils.stopScreenRecording(fakeScreenRecording) }
    }

    @Test
    fun `stopRecording removes recording state so device can record again`() {
        val state = recordingManager.startRecording("device-1", null)
        recordingManager.stopRecording("device-1", state.recordingId)

        val state2 = recordingManager.startRecording("device-1", null)
        assertThat(state2.recordingId).isNotEqualTo(state.recordingId)
    }

    @Test
    fun `stopRecording copies file to output path when specified`() {
        val outputFile = File.createTempFile("output-recording", ".mov")
        outputFile.delete()
        outputFile.deleteOnExit()

        val state = recordingManager.startRecording("device-1", outputFile.absolutePath)

        val result = recordingManager.stopRecording("device-1", state.recordingId)

        assertThat(result.videoPath).isEqualTo(outputFile.absolutePath)
        assertThat(outputFile.exists()).isTrue()
        outputFile.delete()
    }
}
