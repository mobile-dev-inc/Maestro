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

package maestro.cli.command

import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import maestro.Maestro
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.drivers.AndroidDriver
import picocli.CommandLine

@CommandLine.Command(
    name = "events",
    description = [
        "Translate interaction on the connected device to Maestro commands."
    ],
)
class EventsCommand : Runnable {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    override fun run() {
        TestDebugReporter.install(
            debugOutputPathAsString = null,
            flattenDebugOutput = false,
            printToConsole = parent?.verbose == true,
        )

        (Dadb.discover() ?: throw CliError("No Android devices detected")).use { dadb ->
            Maestro.android(AndroidDriver(dadb)).use { maestro ->
                dadb.openShell("getevent -lt").use { stream ->
                    while (true) {
                        val packet = stream.read()
                        if (packet is AdbShellPacket.StdOut) {
                            val output = String(packet.payload, Charsets.UTF_8)
                            print(output)
                        }
                    }
                }
            }
        }
    }
}
