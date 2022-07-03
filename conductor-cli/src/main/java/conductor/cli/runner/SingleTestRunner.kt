package conductor.cli.runner

import conductor.Conductor
import conductor.orchestra.CommandReader
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import java.io.File

class SingleTestRunner(
    private val conductor: Conductor,
    private val testFile: File,
    private val commandReader: CommandReader,
) {

    fun run(): Int {
        AnsiConsole.systemInstall()
        println(Ansi.ansi().eraseScreen())

        val view = ResultView()

        return conductor.use {
            val commandRunner = ConductorCommandRunner(
                conductor = conductor,
                view = view,
                commandReader = commandReader,
            )

            val success = commandRunner.run(testFile)

            if (success) {
                0
            } else {
                1
            }
        }
    }

}