package maestro.cli.runner.resultview

interface ResultView {
    fun setState(state: UiState)

    /**
     * Frames captured while rendering, used to overlay text on screen recordings.
     * Views that do not render to a terminal (or cannot capture frames) may return
     * an empty list.
     */
    fun getFrames(): List<Frame>
}
