package maestro.cli

import org.jline.jansi.Ansi
import org.jline.jansi.AnsiConsole
import org.jline.nativ.CLibrary
import org.jline.nativ.Kernel32
import org.jline.terminal.TerminalBuilder
import picocli.CommandLine
import java.nio.charset.Charset

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

            // CLibrary is POSIX-only; Windows uses Kernel32 instead.
            val stdoutIsTTY = try {
                if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                    Kernel32.isatty(1) != 0
                } else {
                    CLibrary.isatty(1) != 0
                }
            } catch (_: Throwable) {
                System.console() != null
            }
            ansiEnabled = mixin?.enableANSIOutput ?: stdoutIsTTY // Use the param value if it was specified
            Ansi.setEnabled(ansiEnabled)

            if (ansiEnabled) {
                // AnsiConsole creates an AnsiPrintStream using terminal.encoding() but its
                // underlying WriterOutputStream uses terminal.outputEncoding() (from the JVM's
                // stdout.encoding property, which on Windows is the OEM code page e.g. CP850).
                // When these differ (e.g. UTF-8 vs CP850 on Java 18+), multi-byte Unicode
                // characters like ║ (UTF-8: E2 95 91) are decoded as wrong CP850 glyphs.
                // Build the terminal explicitly with matching encodings to avoid this.
                val enc = Charset.defaultCharset()
                AnsiConsole.setTerminal(
                    TerminalBuilder.builder()
                        .system(true)
                        .name("jansi")
                        .encoding(enc)
                        .stdoutEncoding(enc)
                        .stderrEncoding(enc)
                        .build()
                )
                AnsiConsole.systemInstall()
            }
        }
    }
}
