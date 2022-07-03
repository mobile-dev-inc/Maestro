package conductor.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import conductor.ElementLookupPredicate
import conductor.Predicates
import conductor.cli.util.ConductorFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

@Command(
    name = "query",
)
class QueryCommand : Runnable {

    @Option(names = ["-t", "--target"])
    private var target: String? = null

    @Option(names = ["text"])
    private var text: String? = null

    @Option(names = ["id"])
    private var id: String? = null

    @Spec
    lateinit var commandSpec: Model.CommandSpec

    override fun run() {
        if (target !in setOf("android", "ios", null)) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Target must be one of: android, ios"
            )
        }

        ConductorFactory.createConductor(target).use { conductor ->
            val predicates = mutableListOf<ElementLookupPredicate>()

            text?.let {
                predicates += Predicates.textMatches(it.toRegex())
            }

            id?.let {
                predicates += Predicates.idMatches(it.toRegex())
            }

            if (predicates.isEmpty()) {
                throw CommandLine.ParameterException(
                    commandSpec.commandLine(),
                    "Must specify at least one search criteria"
                )
            }

            val elements = conductor.allElementsMatching(
                Predicates.allOf(predicates)
            )

            val mapper = jacksonObjectMapper()
                .writerWithDefaultPrettyPrinter()

            println("Matches: ${elements.size}")
            elements.forEach {
                println(
                    mapper.writeValueAsString(it)
                )
            }
        }
    }

}