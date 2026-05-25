package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.ShowHelpMixin
import maestro.cli.api.ApiClient
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.cli.view.bold
import maestro.cli.view.cyan
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

        val sections = platforms.mapNotNull { p ->
            val key = p.name.lowercase()
            val raw = cloudDevices[key] ?: return@mapNotNull null
            val groups = raw.map { (model, osList) -> DeviceGroup(model, osList) }
            p to groups
        }.filter { it.second.isNotEmpty() }

        if (sections.isEmpty()) {
            println("No cloud devices found")
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
