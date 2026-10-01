package maestro.cli.runner.resultview

interface ResultView {
    fun setState(state: UiState)

    /**
     * Frames captured while rendering, used to overlay text on screen recordings.
     * Once [setState] has been called at least once this contains at least one frame,
     * unless the view was created with frame capture disabled.
     */
    fun getFrames(): List<Frame>
}
