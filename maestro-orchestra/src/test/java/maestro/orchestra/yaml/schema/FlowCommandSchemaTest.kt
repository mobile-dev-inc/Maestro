package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.device.DeviceOrientation
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlFluentCommand
import maestro.orchestra.yaml.stringCommands
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class FlowCommandSchemaTest {

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
    fun `every bare-string command the parser accepts is a command the schema declares`() {
        val declared = FlowCommandSchema.commands().map { it.name }.toSet()

        // `hide keyboard` is a spelling of `hideKeyboard`, not a command of its own. Any other key the
        // parser accepts but YamlFluentCommand does not declare would be a command the schema misses.
        assertThat(stringCommands.keys - declared).containsExactly("hide keyboard")
    }

    /**
     * Every argument name the schema advertises has to be a name Jackson will bind, and every name
     * Jackson binds has to be advertised. Asked of Jackson's own introspection rather than of the
     * `Yaml*` source, so a `@JsonProperty` rename or a `@JsonAlias` the schema does not know about
     * shows up here instead of shipping. No other test in this file writes an optional argument's
     * name into YAML at all, so this is the only thing holding that boundary.
     */
    @Test
    fun `every advertised argument name is a name Jackson binds`() {
        val mapper = jacksonObjectMapper()
        val common = FlowCommandSchema.commonArguments.map { it.name }.toSet()
        val schemas = FlowCommandSchema.commands().associateBy { it.name }
        val mismatches = mutableListOf<String>()

        for (parameter in YamlFluentCommand::class.primaryConstructor!!.parameters) {
            val name = parameter.name?.takeUnless { it.startsWith("_") } ?: continue
            val type = parameter.type.classifier as? KClass<*> ?: continue
            val schema = schemas.getValue(name)
            if (schema.selector || (schema.arguments.isEmpty() && schema.variants.isEmpty())) continue

            for ((shapeType, arguments) in typedShapesOf(schema, type)) {
                val advertised = arguments.flatMap { listOf(it.name) + it.aliases.orEmpty() }.toSet()
                advertised.filterNot { binds(mapper, shapeType.java, it) }
                    .forEach { mismatches += "$name.$it: advertised, not bound" }
                (introspectedNames(mapper, shapeType.java) - advertised - common)
                    .forEach { mismatches += "$name.$it: bound, not advertised" }
            }
        }

        assertThat(mismatches).isEmpty()
    }

    /** The concrete type behind each shape, paired with the arguments the schema gives that shape. */
    private fun typedShapesOf(schema: CommandSchema, type: KClass<*>): List<Pair<KClass<*>, List<ArgumentSchema>>> {
        if (schema.variants.isEmpty()) return listOf(type to schema.arguments)
        return type.sealedSubclasses.map { subclass ->
            val variant = schema.variants.single { it.name == subclass.simpleName }
            subclass to (schema.arguments + variant.arguments)
        }
    }

    /**
     * Whether Jackson reads [name] as a key of [type], asked by handing it that key and seeing whether it
     * comes back as unrecognised. Any other failure -- a missing required field, a null where a value is
     * needed -- means the key was read, which is all this is asking.
     */
    private fun binds(mapper: ObjectMapper, type: Class<*>, name: String): Boolean =
        runCatching { mapper.readValue("""{"$name":null}""", type) }
            .fold({ true }, { it !is UnrecognizedPropertyException })

    /**
     * The keys Jackson's introspection reports for [type]. Used only to find keys the schema has *missed*,
     * because it cannot be probed for: there is nothing to enumerate. It under-reports `@JsonAlias` on a
     * type that also has a `@JsonCreator(DELEGATING)` factory -- `YamlLaunchApp.appId`'s `url` does not
     * appear here even though the parser accepts it -- so this direction is a floor, not a guarantee.
     */
    private fun introspectedNames(mapper: ObjectMapper, type: Class<*>): Set<String> {
        val description = mapper.deserializationConfig.introspect(mapper.typeFactory.constructType(type))
        return description.findProperties()
            .flatMap { property -> listOf(property.name) + property.findAliases().map { it.simpleName } }
            .toSet()
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

    private fun placeholderFor(argument: ArgumentSchema): String = when (argument.kind) {
        ArgumentKind.NUMBER -> "1"
        ArgumentKind.BOOLEAN -> "true"
        ArgumentKind.ENUM -> argument.values!!.first()
        ArgumentKind.ARRAY -> "[]"
        ArgumentKind.OBJECT -> "{}"
        ArgumentKind.STRING -> "\"$referencedFlow\""
        ArgumentKind.SELECTOR, ArgumentKind.ANY -> "\"placeholder\""
    }

    /**
     * Parses through [MaestroFlowParser.parseCommand], not `checkSyntax`. `checkSyntax` stops after
     * deserializing into `YamlFluentCommand` and never runs `toCommands`, where a large share of
     * Maestro's validation lives -- including every `getByName` lookup behind a `@YamlValues` field.
     * Against `checkSyntax` this assertion is vacuous for exactly the arguments the annotation exists
     * for, because they are declared `String` and deserialize whatever they are given.
     */
    private fun assertParses(command: String) {
        try {
            MaestroFlowParser.parseCommand(FLOW_PATH, APP_ID, command)
        } catch (e: Exception) {
            throw AssertionError("The schema advertises a value the parser rejects:\n$command", e)
        }
    }

    private companion object {
        private val FLOW_PATH: Path = Paths.get("test.yaml")
        private const val APP_ID = "com.example.app"
    }
}
