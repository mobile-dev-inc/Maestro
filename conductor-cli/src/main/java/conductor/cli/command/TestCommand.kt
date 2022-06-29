package conductor.cli.command

import conductor.Conductor
import conductor.cli.util.ConductorFactory
import conductor.orchestra.CommandReader
import conductor.orchestra.ConductorCommand
import conductor.orchestra.Orchestra
import conductor.orchestra.yaml.YamlCommandReader
import okio.source
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "test",
)
class TestCommand : Callable<Int> {

    @CommandLine.Parameters
    private lateinit var os: String

    @CommandLine.Parameters
    private lateinit var testFile: File

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (os !in setOf("android", "ios")) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "OS must be one of: android, ios"
            )
        }

        if (!testFile.exists()) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "File not found: $testFile"
            )
        }

        val commands = readCommands(testFile)

        ConductorFactory.createConductor(os).use {
            try {
                Orchestra(it).executeCommands(commands)
            } catch (e: Conductor.NotFoundException) {
                println("Element not found")
                println(e.message)

                println("FAILURE")
                return 1
            }
        }

        println("SUCCESS")

        return 0
    }

    private fun readCommands(file: File): List<ConductorCommand> {
        val reader = resolveCommandReader(file)

        return file.source().use {
            reader.readCommands(file.source())
        }
    }

    private fun resolveCommandReader(file: File): CommandReader {
        if (file.extension == "yaml") {
            return YamlCommandReader()
        }

        throw CommandLine.ParameterException(
            commandSpec.commandLine(),
            "Test file extension is not supported: ${file.extension}"
        )
    }
}
