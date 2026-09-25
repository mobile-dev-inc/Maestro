package maestro

import java.time.Instant

interface ScreenRecording : AutoCloseable {
    val startedAt: Instant?
        get() = null
}
