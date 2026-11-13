package maestro.cli.runner.resultview

import io.ktor.util.encodeBase64
import java.time.Instant

/**
 * Collects what a [ResultView] shows over time, so `maestro record` can overlay it on the screen recording.
 * Views only report to a recorder when given one, so commands that don't render video retain nothing.
 */
class FrameRecorder(private val now: () -> Instant = Instant::now) {

    // Timestamped with when each screen was shown, in epoch milliseconds.
    private val frames = mutableListOf<Frame>()

    /** Records [screen], the full text the view currently shows. */
    fun record(screen: String) {
        frames.add(Frame(now().toEpochMilli(), screen.encodeBase64()))
    }

    /**
     * The screens recorded so far, timestamped relative to [startedAt]: when the screen recording they will be
     * overlaid on began. Starting a recording takes seconds on some devices, so that is later than the first
     * screens, which are shown from the start of the video.
     */
    fun getFrames(startedAt: Instant): List<Frame> {
        return frames.map { it.copy(timestamp = (it.timestamp - startedAt.toEpochMilli()).coerceAtLeast(0)) }
    }
}
