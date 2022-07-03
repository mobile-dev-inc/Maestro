package conductor.cli.runner

import com.sun.nio.file.SensitivityWatchEventModifier
import conductor.Conductor
import conductor.orchestra.CommandReader
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.io.path.name

class ContinuousTestRunner(
    private val conductor: Conductor,
    private val testFile: File,
    private val commandReader: CommandReader,
) {

    fun run() {
        AnsiConsole.systemInstall()
        println(ansi().eraseScreen())

        val view = ResultView()

        val executor = Executors.newSingleThreadExecutor()
        var future: Future<*>? = null

        conductor.use { conductor ->
            val commandRunner = ConductorCommandRunner(
                conductor = conductor,
                view = view,
                commandReader = commandReader,
            )

            watchTestFile {
                cancelFuture(future)

                future = executor.submit {
                    commandRunner.run(testFile)
                }
            }
        }
    }

    private fun cancelFuture(future: Future<*>?) {
        try {
            future?.cancel(true)
            future?.get()
        } catch (ignored: Exception) {
            // Do nothing
        }
    }

    private fun watchTestFile(block: () -> Unit) {
        val watchService = FileSystems.getDefault().newWatchService()

        val pathKey = testFile
            .parentFile
            .toPath()
            .register(
                watchService,
                arrayOf(
                    StandardWatchEventKinds.ENTRY_MODIFY,
                ),
                SensitivityWatchEventModifier.HIGH
            )

        try {
            block()

            while (!Thread.interrupted()) {
                val watchKey = watchService.take()

                watchKey.pollEvents().forEach {
                    val modifiedPath = it.context() as Path
                    if (modifiedPath.name == testFile.name) {
                        block()
                    }
                }

                if (!watchKey.reset()) {
                    watchKey.cancel()
                    watchService.close()
                    break
                }
            }
        } catch (ignored: InterruptedException) {
            // Do nothing
        }

        pathKey.cancel()
    }

}
