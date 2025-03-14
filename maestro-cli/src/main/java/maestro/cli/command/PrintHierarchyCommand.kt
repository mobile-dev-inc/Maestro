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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.yellow
import maestro.utils.CliInsights
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount
import picocli.CommandLine
import java.lang.StringBuilder

@CommandLine.Command(
    name = "hierarchy",
    description = [
        "Print out the view hierarchy of the connected device"
    ],
    hidden = true
)
class PrintHierarchyCommand : Runnable {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        names = ["--android-webview-hierarchy"],
        description = ["Set to \"devtools\" to use Chrome dev tools for Android WebView hierarchy"],
        hidden = true,
    )
    private var androidWebViewHierarchy: String? = null

    override fun run() {
        TestDebugReporter.install(
            debugOutputPathAsString = null,
            flattenDebugOutput = false,
            printToConsole = parent?.verbose == true,
        )

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools")
            val callback: (Insight) -> Unit = {
                val message = StringBuilder()
                val level = it.level.toString().lowercase().replaceFirstChar(Char::uppercase)
                message.append(level.yellow() + ": ")
                it.message.chunkStringByWordCount(12).forEach { chunkedMessage ->
                    message.append("$chunkedMessage ")
                }
                println(message.toString())
            }
            val insights = CliInsights

            insights.onInsightsUpdated(callback)

            val hierarchy = jacksonObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(session.maestro.viewHierarchy().root)

            insights.unregisterListener(callback)

            println(hierarchy)
        }
    }
}
