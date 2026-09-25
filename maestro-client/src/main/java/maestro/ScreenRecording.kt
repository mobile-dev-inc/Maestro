package maestro

import java.time.Instant

interface ScreenRecording : AutoCloseable {
    /** Approximate wall-clock of the first recorded frame. Null when unknown. */
    val startedAt: Instant?
        get() = null
}