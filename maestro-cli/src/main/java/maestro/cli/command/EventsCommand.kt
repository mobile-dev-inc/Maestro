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

import com.fasterxml.jackson.annotation.JsonFilter
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread
import maestro.Maestro
import maestro.TreeNode
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.drivers.AndroidDriver
import maestro.orchestra.yaml.YamlActionBack
import maestro.orchestra.yaml.YamlCoordinateSwipe
import maestro.orchestra.yaml.YamlElementSelector
import maestro.orchestra.yaml.YamlFluentCommand
import maestro.orchestra.yaml.YamlInputText
import maestro.orchestra.yaml.YamlEraseText
import picocli.CommandLine

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
        println("\nMonitoring touch and key events. Press Ctrl+C to exit\n")
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

    // YAML formatter using Maestro's own serializer
    private val yamlMapper = ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)).apply {
        registerModule(KotlinModule.Builder().build())
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        // Configure mixin to ignore duration and optional fields
        addMixIn(YamlCoordinateSwipe::class.java, SwipeMixin::class.java)
    }

    @JsonIgnoreProperties("duration", "optional")
    private abstract class SwipeMixin

    private val eventParser = MaestroEventParser()

    private fun toPercentage(x: Int, y: Int): Pair<Int, Int> {
        val xPercent = ((x.toFloat() / eventParser.screenWidth.toFloat()) * 100).toInt()
        val yPercent = ((y.toFloat() / eventParser.screenHeight.toFloat()) * 100).toInt()
        return Pair(xPercent, yPercent)
    }

    private fun convertToYamlCommand(command: MaestroCommandData): YamlFluentCommand {
        return when {
            command.event == "tapOn" && command.content.id != null -> {
                YamlFluentCommand(
                    tapOn = YamlElementSelector(
                        id = command.content.id,
                        index = command.content.index?.toString()
                    ),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            command.event == "tapOn" && command.content.point != null -> {
                val (xPercent, yPercent) = toPercentage(command.content.point.x, command.content.point.y)
                YamlFluentCommand(
                    tapOn = YamlElementSelector(
                        point = "${xPercent}%, ${yPercent}%"
                    ),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            command.event == "swipe" && command.content.start != null && command.content.end != null -> {
                val (startXPercent, startYPercent) = toPercentage(command.content.start.x, command.content.start.y)
                val (endXPercent, endYPercent) = toPercentage(command.content.end.x, command.content.end.y)
                YamlFluentCommand(
                    swipe = YamlCoordinateSwipe(
                        start = "${startXPercent}%, ${startYPercent}%",
                        end = "${endXPercent}%, ${endYPercent}%",
                        duration = 400,
                        optional = true
                    ),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            command.event == "inputText" && command.content.text != null -> {
                YamlFluentCommand(
                    inputText = YamlInputText(
                        text = command.content.text
                    ),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            command.event == "back" -> {
                YamlFluentCommand(
                    back = YamlActionBack(),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            command.event == "eraseText" -> {
                YamlFluentCommand(
                    eraseText = YamlEraseText(
                        charactersToErase = command.content.count
                    ),
                    _location = JsonLocation(null, 0, 0, 0)
                )
            }
            else -> throw IllegalStateException("Unsupported command type: ${command.event}")
        }
    }

    override fun run() {
        TestDebugReporter.install(
                debugOutputPathAsString = null,
                flattenDebugOutput = false,
                printToConsole = parent?.verbose == true,
        )

        println("\nStarting Maestro events. . .")

        try {
            eventParser.setCommandCallback { command ->
                // Convert to YamlFluentCommand and print
                try {
                    val yamlCommand = convertToYamlCommand(command)
                    // Create a single-element list and serialize it
                    print(yamlMapper.writeValueAsString(listOf(yamlCommand)))
                } catch (e: Exception) {
                    println("\nFailed to convert to YAML: ${e.message}")
                }

                // Print raw JSON only in verbose mode
                if (parent?.verbose == true) {
                    val json = objectMapper.writeValueAsString(command)
                    println("\nDebug JSON output:")
                    println(json)
                }
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
                                    eventParser.setPreEventHierarchy(hierarchyPoller.get())

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
