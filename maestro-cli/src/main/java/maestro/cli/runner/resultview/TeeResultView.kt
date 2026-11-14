package maestro.cli.runner.resultview

/**
 * Passes every state to each of [views], in order. Lets `maestro record` show one view in the terminal while
 * another renders the frames for the video overlay.
 */
class TeeResultView(private vararg val views: ResultView) : ResultView {

    override fun setState(state: UiState) {
        views.forEach { it.setState(state) }
    }
}
