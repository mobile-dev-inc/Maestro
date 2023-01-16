package maestro.cli.view

import maestro.cli.DisableAnsiMixin
import org.fusesource.jansi.Ansi

class ProgressBar(private val width: Int) {

    private var progressWidth: Int? = null
    private var alreadyPrinted = 0

    fun set(progress: Float) {
        if (DisableAnsiMixin.ansiEnabled) {
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
        } else {
            val progressFactor = (progress * width).toInt()
            var amountToAdd = progressFactor - alreadyPrinted
            if (amountToAdd < 0) amountToAdd = 0
            alreadyPrinted = progressFactor
            print(".".repeat(amountToAdd))
        }
    }
}
