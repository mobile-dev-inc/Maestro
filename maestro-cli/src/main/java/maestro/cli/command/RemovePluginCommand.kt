package maestro.cli.command

import maestro.cli.CliError
import maestro.cli.util.PrintUtils.message
import maestro.orchestra.plugin.PluginManager
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "remove-plugin",
    description = ["Remove a Maestro plugin"],
)
class RemovePluginCommand : Callable<Int> {

    @CommandLine.Parameters(
        description = ["Plugin name to remove (without .jar extension)"],
        arity = "1"
    )
    private lateinit var pluginName: String

    override fun call(): Int {
        val removed = PluginManager.uninstallPlugin(pluginName)

        if (removed) {
            message("✓ Removed plugin: $pluginName")
            return 0
        } else {
            throw CliError("Plugin not found: $pluginName\nUse 'maestro list-plugins' to see installed plugins.")
        }
    }
}
