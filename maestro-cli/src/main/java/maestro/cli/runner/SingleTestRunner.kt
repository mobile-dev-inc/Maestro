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

package maestro.cli.runner

import maestro.Maestro
import maestro.orchestra.CommandReader
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import java.io.File

class SingleTestRunner(
    private val maestro: Maestro,
    private val testFile: File,
    private val commandReader: CommandReader,
) {

    fun run(): Int {
        AnsiConsole.systemInstall()
        println(Ansi.ansi().eraseScreen())

        val view = ResultView()

        return maestro.use {
            val commandRunner = MaestroCommandRunner(
                maestro = maestro,
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