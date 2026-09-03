package maestro.orchestra.yaml.schema

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.MaestroFlowParser
import org.junit.jupiter.api.Test

/**
 * The schema's positive claims are cheap to satisfy — a schema that under-claims passes every one of
 * them. These pin the boundary instead: what the schema calls required must actually be rejected when
 * omitted, and what it calls optional must actually be accepted when omitted.
 */
class RequiredClaimTest {

    /**
     * Both commands deserialize their map form through a delegating creator that reads the field with
     * `getOrDefault(name, "")`, so omitting it yields an empty string rather than an error. The schema
     * is right that the field is required; the parser is wrong to accept its absence. Tightening that
     * changes parsing behaviour and is deliberately not part of this change — pinned here so the day it
     * is fixed, this test fails and the exception gets removed rather than lingering.
     */
    private val parserAcceptsOmission = listOf("inputText.text", "evalScript.script")

    @Test
    fun `every argument the schema calls required is rejected when omitted`() {
        val accepted = mutableListOf<String>()

        for (command in testableCommands()) {
            for (omitted in command.arguments.filter { it.required }) {
                if (parses(renderOmitting(command.name, command.arguments, omitted.name))) {
                    accepted += "${command.name}.${omitted.name}"
                }
            }
        }

        assertThat(accepted).containsExactlyElementsIn(parserAcceptsOmission)
    }

    @Test
    fun `every argument the schema calls optional is accepted when omitted`() {
        val rejected = mutableListOf<String>()

        for (command in testableCommands()) {
            for (omitted in command.arguments.filterNot { it.required }) {
                if (!parses(renderOmitting(command.name, command.arguments, omitted.name))) {
                    rejected += "${command.name}.${omitted.name}"
                }
            }
        }

        assertThat(rejected).isEmpty()
    }

    /**
     * Commands with alternative shapes are excluded: dropping a variant's distinguishing argument
     * leaves a shape a sibling variant legitimately accepts — omit `from` from a swipe-by-element and
     * a valid swipe-by-direction remains — so "it still parsed" says nothing about that argument.
     * Their arguments are covered positively by [FlowCommandSchemaTest]. Asserted rather than assumed,
     * so a second variant command cannot join the exclusion silently.
     */
    private fun testableCommands(): List<CommandSchema> {
        val commands = FlowCommandSchema.commands()
        assertThat(commands.filter { it.variants.isNotEmpty() }.map { it.name }).containsExactly("swipe")
        return commands.filter { it.variants.isEmpty() }
    }

    /** Renders the map form carrying every required argument except [omit]. */
    private fun renderOmitting(name: String, arguments: List<ArgumentSchema>, omit: String): String {
        val kept = arguments.filter { it.required && it.name != omit }
        if (kept.isEmpty()) return "$name: {}"
        return kept.joinToString(prefix = "$name:\n", separator = "\n") { "  ${it.name}: ${placeholderFor(it)}" }
    }

    private fun placeholderFor(argument: ArgumentSchema): String = when (argument.kind) {
        ArgumentKind.NUMBER -> "1"
        ArgumentKind.BOOLEAN -> "true"
        ArgumentKind.ENUM -> argument.values!!.first()
        ArgumentKind.ARRAY -> "[]"
        ArgumentKind.OBJECT -> "{}"
        ArgumentKind.STRING, ArgumentKind.SELECTOR, ArgumentKind.ANY -> "\"placeholder\""
    }

    private fun parses(command: String): Boolean =
        runCatching { MaestroFlowParser.checkSyntax(command, null) }.isSuccess
}
