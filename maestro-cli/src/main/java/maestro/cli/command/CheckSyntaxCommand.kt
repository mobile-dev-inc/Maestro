package maestro.cli.command

import maestro.cli.CliError
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "check-syntax",
    description = [
        "Check syntax of Maestro code"
    ]
)
class CheckSyntaxCommand : Callable<Int> {

    @CommandLine.Parameters(
        index = "0",
        description = ["Check syntax of Maestro flow file or \"-\" for stdin"],
    )
    private lateinit var file: File

    override fun call(): Int {
        val maestroCode = if (file.path == "-") {
            System.`in`.readBytes().toString(Charsets.UTF_8)
        } else {
            if (!file.exists()) throw CliError("File does not exist: ${file.absolutePath}")
            file.readText()
        }
        if (maestroCode.isBlank()) throw CliError("Maestro code is empty.")
        try {
            YamlCommandReader.checkSyntax(maestroCode)
            println("OK")
        } catch (e: SyntaxError) {
            throw CliError(e.message)
        }
        return 0
    }
}
