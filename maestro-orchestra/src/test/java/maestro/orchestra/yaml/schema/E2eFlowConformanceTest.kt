package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.stringCommands
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Every flow in `e2e/` is written by hand, for real apps, by people who were not thinking about the
 * schema. Nothing parses them outside the E2E job, which needs devices and takes half an hour, so
 * this is the only fast check that the surface the schema publishes is the surface real flows use.
 */
class E2eFlowConformanceTest {

    private val mapper = ObjectMapper(YAMLFactory())
    private val schema = FlowCommandSchema.commands().associateBy { it.name }

    @Test
    fun `every command an e2e flow writes is a command the schema declares`() {
        val unknown = sortedSetOf<String>()

        for (file in flowFiles()) {
            for ((command, _) in commandsIn(file).orEmpty()) {
                if (command !in schema && command !in stringCommands) unknown += "$command  (${file.name})"
            }
        }

        assertThat(unknown).isEmpty()
    }

    @Test
    fun `every argument an e2e flow writes is an argument the schema declares`() {
        val unknown = sortedSetOf<String>()
        val common = FlowCommandSchema.commonArguments.map { it.name }.toSet()
        val selector = FlowCommandSchema.selectorArguments.map { it.name }.toSet()

        for (file in flowFiles()) {
            for ((command, arguments) in commandsIn(file).orEmpty()) {
                val declared = schema[command] ?: continue
                val known = buildSet {
                    addAll(common)
                    declared.arguments.forEach { add(it.name); addAll(it.aliases.orEmpty()) }
                    declared.variants.forEach { v -> v.arguments.forEach { add(it.name); addAll(it.aliases.orEmpty()) } }
                    if (declared.selector) addAll(selector)
                }
                arguments.filterNot { it in known }.forEach { unknown += "$command.$it  (${file.name})" }
            }
        }

        assertThat(unknown).isEmpty()
    }

    /** Every YAML under e2e/, minus the workspace/config files that are not flows. */
    private fun flowFiles(): List<Path> {
        val root = Paths.get("..", "e2e").toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).asSequence()
            .filter { Files.isRegularFile(it) && it.extension in setOf("yaml", "yml") }
            .filterNot { it.name == "config.yaml" || it.name == "workspace.yaml" }
            .sorted()
            .toList()
    }

    /** The `command name -> argument names` pairs a flow file writes, or null when it is not a flow. */
    private fun commandsIn(file: Path): List<Pair<String, List<String>>>? {
        val documents = runCatching {
            mapper.factory.createParser(file.toFile()).use { parser ->
                mapper.readValues(parser, JsonNode::class.java).readAll()
            }
        }.getOrNull() ?: return null
        val commands = documents.lastOrNull { it.isArray } ?: return null
        return commands.mapNotNull { node ->
            when {
                node.isTextual -> node.asText() to emptyList()
                node.isObject && node.size() == 1 -> {
                    val name = node.fieldNames().next()
                    val value = node.get(name)
                    name to if (value != null && value.isObject) value.fieldNames().asSequence().toList() else emptyList()
                }
                else -> null
            }
        }
    }
}
