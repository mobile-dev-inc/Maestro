package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.PrintUtils
import maestro.cli.view.bold
import maestro.cli.view.cyan
import maestro.cli.view.faint
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "list-devices",
    description = ["List local devices available, grouped by platform"],
)
class ListDevicesCommand : Callable<Int> {

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        names = ["--platform"],
        description = ["Filter by platform: android, ios, web"],
    )
    private var platform: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val platformFilter = platform?.let { input ->
            Platform.fromString(input)
        }

        println("Showing local devices. Use 'maestro list-cloud-devices' to list devices available on Maestro Cloud.".faint())
        println()

        PrintUtils.info("Local Devices", bold = true)
        println("─".repeat(SEPARATOR_WIDTH))

        val devices = DeviceService.listDevices(includeWeb = true)
        val platforms = if (platformFilter != null) listOf(platformFilter) else Platform.entries
        val sections = platforms.map { p -> p to devices.filter { it.platform == p }.groupedByModel() }
            .filter { it.second.isNotEmpty() }

        if (sections.isEmpty()) {
            println("No devices found")
            return 0
        }

        sections.forEachIndexed { idx, (p, groups) ->
            if (idx > 0) println()
            printSection(p, groups)
        }

        return 0
    }

    private data class DeviceGroup(
        val model: String,
        val osList: List<String>,
    )

    private fun List<Device>.groupedByModel(): List<DeviceGroup> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (device in this) {
            if (device.deviceSpec.model.isEmpty()) continue
            val osList = groups.getOrPut(device.deviceSpec.model) { mutableListOf() }
            if (device.deviceSpec.os.isNotEmpty() && device.deviceSpec.os !in osList) {
                osList.add(device.deviceSpec.os)
            }
        }
        return groups.map { (model, osList) -> DeviceGroup(model, osList) }
    }

    private fun printSection(platform: Platform, groups: List<DeviceGroup>) {
        println(platform.description.bold())

        val modelW = groups.maxOf { it.model.length }

        for (g in groups) {
            val osLine = g.osList.joinToString(", ")
            println(row(g.model.cyan().padEnd(modelW + ansiExtra(g.model.cyan())), osLine))
        }
    }

    private fun row(vararg cols: String) = "  " + cols.joinToString("   ")

    private fun ansiExtra(s: String) = s.length - s.replace(ANSI_RE, "").length

    companion object {
        private val ANSI_RE = Regex("\u001B\\[[\\d;]*[^\\d;]")
        private const val SEPARATOR_WIDTH = 53
    }
}
