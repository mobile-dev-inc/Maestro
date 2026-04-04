package maestro.cli.mcp.tools

import maestro.utils.TempFileHandler
import util.LocalSimulatorUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class RecordingState(
    val recordingId: String,
    val deviceId: String,
    val screenRecording: LocalSimulatorUtils.ScreenRecording,
    val outputPath: String?
)

data class StopRecordingResult(
    val videoPath: String
)

class RecordingManager(
    private val localSimulatorUtils: LocalSimulatorUtils
) {
    private val activeRecordings = ConcurrentHashMap<String, RecordingState>()

    fun startRecording(deviceId: String, outputPath: String?): RecordingState {
        val screenRecording = localSimulatorUtils.startScreenRecording(deviceId)
        val recordingId = UUID.randomUUID().toString()
        val state = RecordingState(
            recordingId = recordingId,
            deviceId = deviceId,
            screenRecording = screenRecording,
            outputPath = outputPath
        )
        val existing = activeRecordings.putIfAbsent(deviceId, state)
        if (existing != null) {
            localSimulatorUtils.stopScreenRecording(screenRecording)
            throw IllegalStateException("Recording already active for device $deviceId")
        }
        return state
    }

    fun stopRecording(deviceId: String, recordingId: String): StopRecordingResult {
        val state = activeRecordings[deviceId]
            ?: throw IllegalStateException("No active recording for device $deviceId")
        if (state.recordingId != recordingId) {
            throw IllegalArgumentException("Recording ID mismatch: expected ${state.recordingId}, got $recordingId")
        }

        val videoFile = localSimulatorUtils.stopScreenRecording(state.screenRecording)

        val finalPath = if (state.outputPath != null) {
            val dest = File(state.outputPath)
            dest.parentFile?.mkdirs()
            videoFile.copyTo(dest, overwrite = true)
            videoFile.delete()
            dest.absolutePath
        } else {
            videoFile.absolutePath
        }

        activeRecordings.remove(deviceId)

        return StopRecordingResult(videoPath = finalPath)
    }

    fun shutdown() {
        activeRecordings.values.forEach { state ->
            try {
                state.screenRecording.process.outputStream.close()
                state.screenRecording.process.waitFor(5, TimeUnit.SECONDS)
                if (state.screenRecording.process.isAlive) {
                    state.screenRecording.process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private var defaultInstance: RecordingManager? = null

        fun getDefault(): RecordingManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: run {
                    val tempFileHandler = TempFileHandler()
                    val utils = LocalSimulatorUtils(tempFileHandler)
                    val manager = RecordingManager(utils)
                    Runtime.getRuntime().addShutdownHook(Thread {
                        manager.shutdown()
                        tempFileHandler.close()
                    })
                    defaultInstance = manager
                    manager
                }
            }
        }
    }
}
