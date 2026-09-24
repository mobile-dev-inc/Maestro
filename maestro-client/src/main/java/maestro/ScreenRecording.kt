package maestro

import java.time.Instant

interface ScreenRecording : AutoCloseable {
    /**
     * Wall-clock of (approximately) the first recorded frame, so events stamped on the same
     * clock can be placed on the video's timeline. Null when unknown, e.g. for the no-op
     * returned while another recording is already in progress.
     */
    val startedAt: Instant?
        get() = null
}