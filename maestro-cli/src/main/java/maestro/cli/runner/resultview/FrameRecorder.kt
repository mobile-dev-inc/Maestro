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
     * no frame, so the first screen is always kept. [screen] may be a buffer the view keeps writing to; only
     * a copy of it is retained.
     */
    fun record(screen: CharSequence) {
        if (lastScreen?.contentEquals(screen) == true) return
        val copy = screen.toString()
        lastScreen = copy
        frames.add(Frame(System.currentTimeMillis() - startTimestamp, copy.encodeBase64()))
    }

    fun getFrames(): List<Frame> {
        return frames.toList()
    }
}
