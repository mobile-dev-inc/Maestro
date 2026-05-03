package maestro.cli.command

import maestro.cli.CliError
import maestro.cli.util.PrintUtils.message
import maestro.orchestra.plugin.PluginManager
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "add-plugin",
    description = ["Add a Maestro plugin from a JAR file"],
)
class AddPluginCommand : Callable<Int> {

    @CommandLine.Parameters(
        description = ["Plugin JAR file to add"],
        arity = "1"
    )
    private lateinit var pluginFile: File

    override fun call(): Int {
        try {
            val installedFile = PluginManager.installPlugin(pluginFile)
            val pluginName = installedFile.nameWithoutExtension

            message("✓ Added plugin: ${installedFile.name}")
            message("  Location: ${installedFile.absolutePath}")
            message("")
            message("To use this plugin:")
            message("  maestro test flow.yaml --plugin $pluginName")

            return 0
        } catch (e: IllegalArgumentException) {
            throw CliError(e.message ?: "Failed to add plugin")
        } catch (e: Exception) {
            throw CliError("Failed to add plugin: ${e.message}")
        }
    }
}
