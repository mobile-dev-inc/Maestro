package maestro.cli

import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.fusesource.jansi.internal.CLibrary
import picocli.CommandLine

class DisableAnsiMixin {
    @CommandLine.Option(
        names = ["--no-color", "--no-ansi"],
        negatable = true,
        description = ["Enable / disable colors and ansi output"]
    )
    var enableANSIOutput = true

    companion object {
        var ansiEnabled = true
            private set

        fun executionStrategy(parseResult: CommandLine.ParseResult): Int {
            applyCLIMixin(parseResult)
            PluginsMixin.applyPluginsDir(parseResult)
            return CommandLine.RunLast().execute(parseResult)
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

        private fun applyCLIMixin(parseResult: CommandLine.ParseResult) {
            // Find the first mixin for which of the enable-ansi parameter was specified
            val parserWithANSIOption = findFirstParserWithMatchedParamLabel(parseResult, "<enableANSIOutput>")
            val mixin = parserWithANSIOption?.commandSpec()?.mixins()?.values?.firstNotNullOfOrNull { it.userObject() as? DisableAnsiMixin }

            val stdoutIsTTY = CLibrary.isatty(CLibrary.STDOUT_FILENO) != 0
            ansiEnabled = mixin?.enableANSIOutput // Use the param value if it was specified
                ?: stdoutIsTTY // Otherwise fall back to checking if output is a tty

            Ansi.setEnabled(ansiEnabled)

            if (ansiEnabled) {
                AnsiConsole.systemInstall()
            }
        }
    }
}
