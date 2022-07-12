/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

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
            .absoluteFile
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
