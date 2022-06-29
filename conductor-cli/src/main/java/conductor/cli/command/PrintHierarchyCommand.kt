package conductor.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import conductor.cli.util.ConductorFactory
import picocli.CommandLine

@CommandLine.Command(
    name = "hierarchy",
)
class PrintHierarchyCommand : Runnable {

    @CommandLine.Parameters
    private lateinit var os: String

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun run() {
        if (os !in setOf("android", "ios")) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "OS must be one of: android, ios"
            )
        }

        ConductorFactory.createConductor(os).use {
            val hierarchy = jacksonObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(it.viewHierarchy())

            println(hierarchy)
        }
    }
}
