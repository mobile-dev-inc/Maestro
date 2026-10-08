package maestro.orchestra.debug

import maestro.Maestro
import maestro.ScreenRecording
import okio.Sink
import java.io.File

/**
 * Starts a screen recording that writes into [file] through [sink].
 *
 * When no recording starts, [sink] is closed and [file] deleted, so no empty recording is left
 * on disk or reported as an artifact. That covers both outcomes: a recording already in
 * progress (returns null) and a driver that failed to start (the failure is rethrown).
 */
internal suspend fun Maestro.startScreenRecordingInto(sink: Sink, file: File): ScreenRecording? {
    val recording = try {
        startScreenRecording(sink)
    } catch (e: Exception) {
        discardRecordingOutput(sink, file)
        throw e
    }
    if (recording == null) discardRecordingOutput(sink, file)
    return recording
}

private fun discardRecordingOutput(sink: Sink, file: File) {
    // A failed close must not mask why the recording did not start.
    runCatching { sink.close() }
    file.delete()
}
