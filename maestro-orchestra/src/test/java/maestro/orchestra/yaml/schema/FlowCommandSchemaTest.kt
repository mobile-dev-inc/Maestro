package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.device.DeviceOrientation
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlFluentCommand
import maestro.orchestra.yaml.stringCommands
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class FlowCommandSchemaTest {

    @Test
    fun `covers exactly the commands YamlFluentCommand declares`() {
        val declared = YamlFluentCommand::class.primaryConstructor!!.parameters
            .mapNotNull { it.name }
            .filterNot { it.startsWith("_") }

        assertThat(FlowCommandSchema.commands().map { it.name }).containsExactlyElementsIn(declared)
    }

    /**
     * The guarantee that makes the schema worth publishing: every value it advertises is a value the
     * parser actually accepts. Each declared enum value is fed back through real parsing, so an enum
     * renamed without its `@JsonProperty` fails here instead of shipping a schema that lies.
     */
    @Test
    fun `every advertised enum value parses`() {
        for (command in FlowCommandSchema.commands()) {
            command.shorthand
                ?.takeIf { it.kind == ArgumentKind.ENUM }
                ?.values.orEmpty()
                .forEach { value -> assertParses("${command.name}: $value") }

            for (shape in shapesOf(command)) {
                shape.filter { it.kind == ArgumentKind.ENUM }.forEach { argument ->
                    argument.values.orEmpty().forEach { value ->
                        assertParses(render(command.name, shape, argument.name to value))
                    }
                }
            }
        }
    }

    /**
     * `pressKey.key` and `setOrientation.orientation` have to stay `String` to keep accepting `${VAR}`,
     * so only their `@YamlValues` annotation tells the schema what the parser's `getByName` will accept.
     * Both the map form and the shorthand must carry the vocabulary, and `KeyCode` must be spelled by
     * its `description`, not its constant names. The words are read back off the enums rather than
     * listed here, so this test cannot become a second copy of them.
     */
    @Test
    fun `a String field annotated with YamlValues advertises its vocabulary`() {
        val commands = FlowCommandSchema.commands().associateBy { it.name }

        val keys = KeyCode.entries.map { it.description }
        val pressKey = commands.getValue("pressKey")
        assertThat(pressKey.arguments.single { it.name == "key" })
            .isEqualTo(ArgumentSchema("key", ArgumentKind.ENUM, required = true, values = keys))
        assertThat(pressKey.shorthand).isEqualTo(ShorthandSchema(ArgumentKind.ENUM, keys))

        val orientations = DeviceOrientation.entries.map { it.name }
        val setOrientation = commands.getValue("setOrientation")
        assertThat(setOrientation.arguments.single { it.name == "orientation" })
            .isEqualTo(ArgumentSchema("orientation", ArgumentKind.ENUM, required = true, values = orientations))
        assertThat(setOrientation.shorthand).isEqualTo(ShorthandSchema(ArgumentKind.ENUM, orientations))
    }

    /**
     * A hand-written deserializer is where the schema can go blind: it may accept shapes the data
     * class does not reveal. Every command that has one must be describable — as a selector, through
     * alternative shapes, through an enum vocabulary, or through a single-value form. Adding a custom
     * deserializer without exposing what it accepts fails here.
     */
    @Test
    fun `commands with a custom deserializer are describable`() {
        val schemas = FlowCommandSchema.commands().associateBy { it.name }

        val opaque = YamlFluentCommand::class.primaryConstructor!!.parameters
            .mapNotNull { parameter ->
                val name = parameter.name?.takeUnless { it.startsWith("_") } ?: return@mapNotNull null
                val type = parameter.type.classifier as? KClass<*> ?: return@mapNotNull null
                if (!type.java.isAnnotationPresent(JsonDeserialize::class.java)) return@mapNotNull null

                val schema = schemas.getValue(name)
                val describable = schema.selector ||
                    schema.variants.isNotEmpty() ||
                    schema.shorthand != null ||
                    shapesOf(schema).any { shape -> shape.any { it.kind == ArgumentKind.ENUM } }
                name.takeUnless { describable }
            }

        assertThat(opaque).isEmpty()
    }

    @Test
    fun `bare-string commands match the parser's stringCommands map`() {
        val commands = FlowCommandSchema.commands()
        val declared = commands.map { it.name }.toSet()

        assertThat(commands.filter { it.bareString }.map { it.name })
            .containsExactlyElementsIn(stringCommands.keys.filter { it in declared })

        // `hide keyboard` is a spelling of `hideKeyboard`, not a command of its own. Any other key the
        // parser accepts but YamlFluentCommand does not declare would be a command the schema misses.
        assertThat(stringCommands.keys - declared).containsExactly("hide keyboard")
    }

    private fun shapesOf(command: CommandSchema): List<List<ArgumentSchema>> {
        if (command.variants.isEmpty()) return listOf(command.arguments)
        return command.variants.map { command.arguments + it.arguments }
    }

    /** Renders `name:` with every required argument plus [override], so the parser sees a valid command. */
    private fun render(name: String, shape: List<ArgumentSchema>, override: Pair<String, String>): String {
        val arguments = shape
            .filter { it.required || it.name == override.first }
            .associate { it.name to if (it.name == override.first) override.second else placeholderFor(it) }
        return arguments.entries.joinToString(prefix = "$name:\n", separator = "\n") { "  ${it.key}: ${it.value}" }
    }

    private fun placeholderFor(argument: ArgumentSchema): String = when (argument.kind) {
        ArgumentKind.NUMBER -> "1"
        ArgumentKind.BOOLEAN -> "true"
        ArgumentKind.ENUM -> argument.values!!.first()
        ArgumentKind.ARRAY -> "[]"
        ArgumentKind.OBJECT -> "{}"
        ArgumentKind.STRING, ArgumentKind.SELECTOR, ArgumentKind.ANY -> "\"placeholder\""
    }

    private fun assertParses(command: String) {
        try {
            MaestroFlowParser.checkSyntax(command, null)
        } catch (e: Exception) {
            throw AssertionError("The schema advertises a value the parser rejects:\n$command", e)
        }
    }
}
