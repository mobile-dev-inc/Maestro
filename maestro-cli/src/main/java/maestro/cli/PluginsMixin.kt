package maestro.cli

import maestro.plugins.PluginRegistry
import picocli.CommandLine
import java.nio.file.Path

class PluginsMixin {
    @CommandLine.Option(
        names = ["--plugins-dir"],
        description = ["Custom directory for external plugins (default: ~/.maestro/plugins)"],
        paramLabel = "<plugins-dir>"
    )
    var pluginsDir: String? = null

    companion object {
        fun applyPluginsDir(parseResult: CommandLine.ParseResult) {
            val parserWithPluginsOption = findFirstParserWithMatchedParamLabel(parseResult, "<plugins-dir>")
            val mixin = parserWithPluginsOption?.commandSpec()?.mixins()?.values?.firstNotNullOfOrNull { it.userObject() as? PluginsMixin }
            
            if (mixin?.pluginsDir != null) {
                PluginRegistry.setPluginsDirectory(Path.of(mixin.pluginsDir!!))
            }
        }

        private fun findFirstParserWithMatchedParamLabel(parseResult: CommandLine.ParseResult, paramLabel: String): CommandLine.ParseResult? {
            val found = parseResult.matchedOptions().find { it.paramLabel() == paramLabel }
            if (found != null) {
                return parseResult
            }

            parseResult.subcommands().forEach {
                return findFirstParserWithMatchedParamLabel(it, paramLabel) ?: return@forEach
            }

            return null
        }
    }
}
