package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.api.ApiClient
import maestro.cli.api.CloudPlatformDevices
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.cli.view.bold
import maestro.cli.view.cyan
import maestro.cli.view.faint
import maestro.cli.view.yellow
import maestro.device.Platform
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "list-cloud-devices",
    description = ["List devices available on Maestro Cloud, grouped by platform"],
)
class ListCloudDevicesCommand : Callable<Int> {

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

        val apiClient = ApiClient(EnvUtils.BASE_API_URL)

        println()
        PrintUtils.info("Cloud Devices", bold = true)
        println("─".repeat(SEPARATOR_WIDTH))

        val cloudDevices = try {
            apiClient.listCloudDevices()
        } catch (e: ApiClient.ApiException) {
            if (e.statusCode == null) PrintUtils.err("Unable to reach Maestro Cloud. Please check your network connection and try again.")
            throw e
        }

        val platformOrder = listOf(Platform.IOS, Platform.ANDROID, Platform.WEB)
        val platforms = if (platformFilter != null) listOf(platformFilter) else platformOrder
        val lines = renderDevices(cloudDevices, platforms)

        if (lines.isEmpty()) {
            println("No cloud devices found")
            return 0
        }
        lines.forEach(::println)

        return 0
    }

    internal fun renderDevices(cloudDevices: Map<String, CloudPlatformDevices>, platforms: List<Platform>): List<String> {
        val sections = platforms.mapNotNull { p ->
            cloudDevices[p.name.lowercase()]?.takeIf { it.devices.isNotEmpty() }?.let { p to it }
        }
        if (sections.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        sections.forEachIndexed { idx, (p, section) ->
            if (idx > 0) lines += ""
            lines += renderSection(p, section)
        }

        val deprecatedByDate = sections
            .flatMap { (_, section) -> section.devices.flatMap { it.osVersions } }
            .mapNotNull { os -> os.removalDate?.let { it to os.id } }
            .groupBy({ it.first }, { it.second })
        if (deprecatedByDate.isNotEmpty()) lines += ""
        deprecatedByDate.forEach { (date, ids) ->
            lines += "* Deprecated, removed from Maestro Cloud on $date: ${ids.distinct().joinToString(", ")}".yellow()
        }
        return lines
    }

    private fun renderSection(platform: Platform, section: CloudPlatformDevices): List<String> {
        val header = platform.description.bold() +
            (section.default?.let { "   default: ${it.model} on ${it.os}".faint() } ?: "")
        val modelW = section.devices.maxOf { it.model.length }
        return listOf(header) + section.devices.map { device ->
            val osLine = device.osVersions.joinToString(", ") { os ->
                if (os.removalDate != null) "${os.id}*".yellow() else os.id
            }
            val model = device.model.cyan().padEnd(modelW + ansiExtra(device.model.cyan()))
            row(model, osLine) + (device.removalDate?.let { "   deprecated, removed $it".yellow() } ?: "")
        }
    }

    private fun row(vararg cols: String) = "  " + cols.joinToString("   ")

    private fun ansiExtra(s: String) = s.length - s.replace(ANSI_RE, "").length

    companion object {
        private val ANSI_RE = Regex("\u001B\\[[\\d;]*[^\\d;]")
        private const val SEPARATOR_WIDTH = 53
    }
}
