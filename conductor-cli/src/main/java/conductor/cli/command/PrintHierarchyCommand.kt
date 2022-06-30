package conductor.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import conductor.cli.util.ConductorFactory
import picocli.CommandLine

@CommandLine.Command(
    name = "hierarchy",
)
class PrintHierarchyCommand : Runnable {

    @CommandLine.Option(names = ["-t", "--target"])
    private var target: String? = null

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun run() {
        if (target !in setOf("android", "ios", null)) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Target must be one of: android, ios"
            )
        }

        ConductorFactory.createConductor(target).use {
            println("Printing hierarchy from ${it.deviceName()}")

            val hierarchy = jacksonObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(it.viewHierarchy())

            println(hierarchy)
        }
    }
}
