package maestro.cli.graphics

import maestro.cli.runner.resultview.Frame
import java.io.File

interface VideoRenderer {
    fun render(
        screenRecording: File,
        textFrames: List<Frame>,
    )
}
