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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import kotlin.concurrent.fixedRateTimer
import maestro.Maestro
import maestro.TreeNode
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.drivers.AndroidDriver
import picocli.CommandLine
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private fun AdbShellStream.lines(): Sequence<String> {
    return sequence {
        var prev = ""
        while (true) {
            val packet = read()
            if (packet is AdbShellPacket.StdOut) {
                val output = prev + String(packet.payload, Charsets.UTF_8)
                val lines = output.split("\n")
                lines.dropLast(1).forEach { line -> yield(line) }
                prev = lines.last()
            }
            if (packet is AdbShellPacket.Exit) {
                if (prev.isNotEmpty()) {
                    yield(prev)
                }
                break
            }
        }
    }
}

class HierarchyPoller(private val maestro: Maestro) {

    private val hierarchyRef = AtomicReference<TreeNode>(null)

    fun get(): TreeNode? {
        return hierarchyRef.get()
    }

    fun start() {
        thread {
            while (true) {
                val hierarchy = maestro.viewHierarchy()
                hierarchyRef.set(hierarchy.root)
            }
        }
    }
}

@CommandLine.Command(
        name = "events",
        description = ["Translate interaction on the connected device to Maestro commands."],
)
class EventsCommand : Runnable {

    @CommandLine.Mixin var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand private val parent: App? = null

    // Storage for collected events
    private var interactionEvents = mutableListOf<EventData>()
    private var processingTimer: java.util.Timer? = null
    private val bufferTimeoutMs = 1000L

    // JSON formatter
    private val objectMapper = ObjectMapper().apply { enable(SerializationFeature.INDENT_OUTPUT) }

    override fun run() {
        TestDebugReporter.install(
                debugOutputPathAsString = null,
                flattenDebugOutput = false,
                printToConsole = parent?.verbose == true,
        )

        println("Monitoring touch and key events. Press Ctrl+C to exit")

        try {
            val eventParser = MaestroEventParser()

            eventParser.setCommandCallback { command ->
                val json = objectMapper.writeValueAsString(command)
                println(json)
            }

            processingTimer =
                    fixedRateTimer(
                            name = "event-processor",
                            initialDelay = bufferTimeoutMs,
                            period = bufferTimeoutMs
                    ) { processBufferedEvents() }

            (Dadb.discover() ?: throw CliError("No Android devices detected")).use { dadb ->
                Maestro.android(AndroidDriver(dadb)).use { maestro ->
                    val hierarchyPoller = HierarchyPoller(maestro)
                    hierarchyPoller.start()

                    dadb.openShell("getevent -lt").use { stream ->
                        stream.lines().forEach { line ->
                            if (line.contains("/dev/input/event")) {
                                val eventData = eventParser.parseEventData(line)
                                if (eventData != null) {
                                    synchronized(interactionEvents) {
                                        interactionEvents.add(eventData)
                                    }
                                    eventParser.handleEvent(eventData, parent?.verbose == true)
                                }
                            } else if (parent?.verbose == true) {
                                println(line)
                            }
                        }
                    }
                }
            }
        } catch (e: DeviceDetectionException) {
            println("Error: ${e.message}")
        } finally {
            processingTimer?.cancel()
        }
    }

    private fun processBufferedEvents() {
        synchronized(interactionEvents) { interactionEvents.clear() }
    }
}
