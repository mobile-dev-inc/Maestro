package conductor.cli.command

import conductor.cli.runner.ContinuousTestRunner
import conductor.cli.runner.SingleTestRunner
import conductor.cli.util.ConductorFactory
import conductor.orchestra.CommandReader
import conductor.orchestra.yaml.YamlCommandReader
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "test",
)
class TestCommand : Callable<Int> {

    @CommandLine.Parameters
    private lateinit var testFile: File

    @Option(names = ["-t", "--target"])
    private var target: String? = null

    @Option(names = ["-c", "--continuous"])
    private var continuous: Boolean = false

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (target !in setOf("android", "ios", null)) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Target must be one of: android, ios"
            )
        }

        if (!testFile.exists()) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "File not found: $testFile"
            )
        }

        val conductor = ConductorFactory.createConductor(target)
        val commandReader = resolveCommandReader(testFile)

        return if (continuous) {
            ContinuousTestRunner(conductor, testFile, commandReader).run()
            0
        } else {
            SingleTestRunner(conductor, testFile, commandReader).run()
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
