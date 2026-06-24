package maestro.cli.command

import maestro.cli.util.PrintUtils.message
import maestro.orchestra.plugin.PluginManager
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "list-plugins",
    description = ["List installed Maestro plugins"],
)
class ListPluginsCommand : Callable<Int> {

    override fun call(): Int {
        val plugins = PluginManager.listPlugins()

        if (plugins.isEmpty()) {
            message("No plugins installed.")
            message("")
            message("Add a plugin:")
            message("  maestro add-plugin <plugin.jar>")
            return 0
        }

        message("Installed plugins (${plugins.size}):")
        message("")

        plugins.sortedBy { it.name }.forEach { plugin ->
            val pluginName = plugin.nameWithoutExtension
            message("  • $pluginName")
            message("    ${plugin.absolutePath}")
        }

        message("")
        message("Usage:")
        message("  maestro test flow.yaml --plugin <plugin-name>")

        return 0
    }
}
