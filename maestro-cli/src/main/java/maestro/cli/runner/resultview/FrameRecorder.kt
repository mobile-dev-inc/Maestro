package maestro.cli.runner.resultview

import io.ktor.util.encodeBase64

/**
 * Collects what a [ResultView] shows over time, so `maestro record` can overlay it on the screen recording.
 * Views only report to a recorder when given one, so commands that don't render video retain nothing.
 */
class FrameRecorder {

    private val startTimestamp = System.currentTimeMillis()

    private val frames = mutableListOf<Frame>()

    private var lastScreen: String? = null

    /**
     * Records [screen], the full text the view currently shows. A screen identical to the previous one adds
     * no frame, so the first screen is always kept.
     */
    fun record(screen: String) {
        if (screen == lastScreen) return
        lastScreen = screen
        frames.add(Frame(System.currentTimeMillis() - startTimestamp, screen.encodeBase64()))
    }

    fun getFrames(): List<Frame> {
        return frames.toList()
    }
}
