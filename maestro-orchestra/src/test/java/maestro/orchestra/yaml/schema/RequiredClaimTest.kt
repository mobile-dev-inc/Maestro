package maestro.orchestra.yaml.schema

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The schema's positive claims are cheap to satisfy — a schema that under-claims passes every one of
 * them. These pin the boundary instead: what the schema calls required must actually be rejected when
 * omitted, and the form the schema says is enough must actually parse.
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
                if (parses(render(command, omit = omitted.name))) {
                    accepted += "${command.name}.${omitted.name}"
                }
            }
        }

        assertThat(accepted).containsExactlyElementsIn(parserAcceptsOmission)
    }

    /**
     * The other direction, and the one a consumer feels first: a command written with only what the
     * schema says it needs has to parse. This is one question per command rather than one per optional
     * argument — optional arguments are never rendered, so the form under test is the same whichever
     * one you think of as omitted.
     */
    @Test
    fun `every command parses carrying only what the schema says it requires`() {
        val rejected = testableCommands()
            .filterNot { parses(render(it)) }
            .map { it.name }

        assertThat(rejected).isEmpty()
    }

    /**
     * [YamlRequiresOneOf] is a hand-written claim about a rule that lives inside `toCommands`, so unlike
     * everything else in the schema it can simply be wrong: a typo names an argument that does not exist,
     * and a rule on a command that does not need one is never noticed. Each part of the claim is checked
     * against the parser here.
     */
    @Test
    fun `every one-of rule says what the parser actually enforces`() {
        for (command in testableCommands()) {
            val rule = command.requiredOneOf ?: continue
            val declared = command.arguments.associateBy { it.name }

            assertWithMessage("${command.name} names arguments it does not have")
                .that(rule.names - declared.keys).isEmpty()
            rule.names.forEach {
                assertWithMessage("${command.name}.$it is in a one-of group and also required")
                    .that(declared.getValue(it).required).isFalse()
            }

            // The gap the annotation exists to close: without it the schema says the empty form is fine.
            assertWithMessage("${command.name}: {} parses, so it needs no one-of rule")
                .that(parses("${command.name}: {}")).isFalse()

            // Each member on its own is enough.
            rule.names.forEach { member ->
                assertWithMessage("${command.name} carrying only $member is rejected")
                    .that(parses(render(command, oneOfMembers = listOf(member)))).isTrue()
            }

            // And `exclusive` says whether all of them together are refused.
            if (rule.names.size > 1) {
                assertWithMessage("${command.name} declares exclusive=${rule.exclusive}")
                    .that(parses(render(command, oneOfMembers = rule.names))).isEqualTo(!rule.exclusive)
            }
        }
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

    /**
     * The map form carrying everything the schema says the command needs, minus [omit]: every required
     * argument, plus one member of a [CommandSchema.requiredOneOf] group when the command declares one.
     */
    private fun render(
        command: CommandSchema,
        omit: String? = null,
        oneOfMembers: List<String> = command.requiredOneOf?.names?.take(1).orEmpty(),
    ): String {
        val kept = command.arguments.filter { (it.required || it.name in oneOfMembers) && it.name != omit }
        if (kept.isNotEmpty()) {
            return kept.joinToString(prefix = "${command.name}:\n", separator = "\n") { "  ${it.name}: ${placeholderFor(it)}" }
        }
        // A command with no named arguments at all may have no map form either -- `action` is a
        // plain `String` on YamlFluentCommand -- so write its single-value form. Gated on there being
        // no arguments rather than none *kept*: a command whose one required argument was just omitted
        // still has a map form, and writing its shorthand would test a different, complete command.
        val writesOnlyAValue = command.arguments.isEmpty() && command.variants.isEmpty()
        val shorthand = command.shorthand?.takeIf { omit == null && writesOnlyAValue }
            ?: return "${command.name}: {}"
        return "${command.name}: ${placeholderFor(shorthand.kind, shorthand.values)}"
    }

    /**
     * A real flow on disk. `runFlow`, `runScript` and `retry` take a path in a plain `String` argument
     * and read it during `toCommands`, so a literal placeholder makes them fail for a reason that has
     * nothing to do with the schema.
     */
    private val referencedFlow: String by lazy {
        TempFileHandler().createTempFile(suffix = ".yaml")
            .apply { writeText("appId: com.example.app\n---\n- back\n") }
            .absolutePath
    }

    private fun placeholderFor(argument: ArgumentSchema): String =
        placeholderFor(argument.kind, argument.values)

    private fun placeholderFor(kind: ArgumentKind, values: List<String>?): String = when (kind) {
        ArgumentKind.NUMBER -> "1"
        ArgumentKind.BOOLEAN -> "true"
        ArgumentKind.ENUM -> values!!.first()
        ArgumentKind.ARRAY -> "[]"
        ArgumentKind.OBJECT -> "{}"
        ArgumentKind.STRING -> "\"$referencedFlow\""
        ArgumentKind.SELECTOR, ArgumentKind.ANY -> "\"placeholder\""
    }

    /** See `FlowCommandSchemaTest.assertParses` for why this is `parseCommand` and not `checkSyntax`. */
    private fun parses(command: String): Boolean =
        runCatching { MaestroFlowParser.parseCommand(FLOW_PATH, APP_ID, command) }.isSuccess

    private companion object {
        private val FLOW_PATH: Path = Paths.get("test.yaml")
        private const val APP_ID = "com.example.app"
    }
}
