package maestro.cli.graphics

import maestro.cli.runner.resultview.AnsiResultView
import java.io.File

interface VideoRenderer {
    fun render(
        screenRecording: File,
        textFrames: List<AnsiResultView.Frame>,
    )
}
