package maestro.cli.view

import org.fusesource.jansi.Ansi

class ProgressBar(private val width: Int) {

    private var progressWidth: Int? = null

    fun set(progress: Float) {
        val progressWidth = (progress * width).toInt()
        if (progressWidth == this.progressWidth) return
        this.progressWidth = progressWidth
        val ansi = Ansi.ansi()
        ansi.cursorToColumn(0)
        ansi.fgCyan()
        repeat(progressWidth) { ansi.a("█") }
        repeat(width - progressWidth) { ansi.a("░") }
        ansi.fgDefault()
        System.err.print(ansi)
    }
}