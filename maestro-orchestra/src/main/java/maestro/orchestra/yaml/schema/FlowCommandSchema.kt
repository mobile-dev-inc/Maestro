package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.orchestra.yaml.YamlElementSelectorUnion
import maestro.orchestra.yaml.YamlFluentCommand
import maestro.orchestra.yaml.stringCommands
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/** How a YAML value is written. */
enum class ArgumentKind {
    STRING,
    NUMBER,
    BOOLEAN,

    /** A fixed vocabulary; the accepted words are in [ArgumentSchema.values]. */
    ENUM,

    /** A list of values. */
    ARRAY,

    /** An element selector — a string, or a map of `text` / `id` / `index` / … */
    SELECTOR,

    /** A map of further arguments. */
    OBJECT,

    /** Any scalar; the parser coerces whatever it is given (`inputText: 42`). */
    ANY,
}

data class ArgumentSchema(
    val name: String,
    val kind: ArgumentKind,
    val required: Boolean,
    /**
     * The accepted YAML words, for [ArgumentKind.ENUM] arguments only. Read from the `@JsonProperty`
     * wire name of each enum constant, so this cannot disagree with what the parser accepts.
     */
    val values: List<String>? = null,
)

/** One of the alternative shapes a command accepts, e.g. `swipe` by direction vs. by coordinates. */
data class VariantSchema(
    val name: String,
    val arguments: List<ArgumentSchema>,
)

/** The single-value form of a command, e.g. `openLink: https://example.com`. */
data class ShorthandSchema(
    val kind: ArgumentKind,
    val values: List<String>? = null,
)

data class CommandSchema(
    /** The name as written in YAML, e.g. `tapOn`. */
    val name: String,

    /** The command's value is an element selector, e.g. `tapOn: "Login"`. */
    val selector: Boolean,

    /** The command may be written with no value at all, e.g. `- back`. */
    val bareString: Boolean,

    /** Present when the command also accepts a single value instead of a map. */
    val shorthand: ShorthandSchema?,

    /**
     * The command's named arguments, minus [FlowCommandSchema.commonArguments]. When [variants] is
     * non-empty these are only the arguments every variant shares.
     */
    val arguments: List<ArgumentSchema>,

    /** Non-empty when the command accepts several alternative shapes. */
    val variants: List<VariantSchema>,
)

/**
 * The Maestro flow-command surface, derived by reflection from the very types the YAML parser uses:
 * [YamlFluentCommand]'s constructor, each command's `Yaml*` data class, and the `stringCommands` map
 * the parser consults for bare-string commands. Nothing here is hand-maintained, so it cannot drift
 * from the parser.
 *
 * This is an API, not an artifact: any JVM consumer already depending on `dev.mobile:maestro-orchestra`
 * calls [commands] (or [asJson] for the same thing as JSON). There is no schema file to publish, no URL
 * to fetch and no path to hardcode — the surface ships inside the jar and moves with the dependency.
 */
object FlowCommandSchema {

    /** Arguments every command inherits. Consumers render these once rather than per command. */
    val commonArguments: List<ArgumentSchema> = listOf(
        ArgumentSchema("label", ArgumentKind.STRING, required = false),
        ArgumentSchema("optional", ArgumentKind.BOOLEAN, required = false),
    )

    private val commonArgumentNames = commonArguments.map { it.name }.toSet()

    fun commands(): List<CommandSchema> {
        return YamlFluentCommand::class.primaryConstructor!!.parameters
            .mapNotNull { parameter ->
                // `_sourceInfo` and friends are parser bookkeeping, not commands.
                val name = parameter.name?.takeUnless { it.startsWith("_") } ?: return@mapNotNull null
                val type = parameter.type.classifier as? KClass<*> ?: return@mapNotNull null
                schemaOf(name, type)
            }
    }

    fun asJson(): String {
        val document = mapOf(
            "commonArguments" to commonArguments,
            "commands" to commands(),
        )
        return ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(document)
    }

    private fun schemaOf(name: String, type: KClass<*>): CommandSchema {
        val bareString = name in stringCommands
        val kind = kindOf(type)

        if (kind == ArgumentKind.SELECTOR) {
            return CommandSchema(name, selector = true, bareString, null, emptyList(), emptyList())
        }

        // A command whose value is a plain scalar, e.g. `openBrowser: https://example.com`.
        if (kind != ArgumentKind.OBJECT) {
            val shorthand = ShorthandSchema(kind, enumValuesOf(type))
            return CommandSchema(name, selector = false, bareString, shorthand, emptyList(), emptyList())
        }

        val subclasses = type.sealedSubclasses
        val shorthand = shorthandOf(type)
        if (subclasses.isEmpty()) {
            return CommandSchema(name, false, bareString, shorthand, argumentsOf(type), emptyList())
        }

        // A sealed command type is one command with alternative shapes. Arguments every shape accepts
        // are hoisted onto the command; each variant keeps only what distinguishes it.
        val perVariant = subclasses.associateWith { argumentsOf(it) }
        val shared = perVariant.values
            .reduce { acc, arguments -> acc.filter { it in arguments } }
        return CommandSchema(
            name = name,
            selector = false,
            bareString = bareString,
            shorthand = shorthand,
            arguments = shared,
            variants = perVariant.map { (subclass, arguments) ->
                VariantSchema(subclass.simpleName!!, arguments - shared.toSet())
            },
        )
    }

    private fun argumentsOf(type: KClass<*>): List<ArgumentSchema> {
        return type.primaryConstructor?.parameters.orEmpty()
            .mapNotNull { parameter ->
                val name = parameter.name?.takeUnless { it in commonArgumentNames } ?: return@mapNotNull null
                val argumentType = parameter.type.classifier as? KClass<*>
                val kind = kindOf(argumentType)
                ArgumentSchema(
                    name = name,
                    kind = kind,
                    required = !parameter.isOptional,
                    values = argumentType?.let(::enumValuesOf),
                )
            }
    }

    /**
     * The value form a command accepts alongside its map form, read from the `@JsonCreator(DELEGATING)`
     * factory the parser already uses for it.
     */
    private fun shorthandOf(type: KClass<*>): ShorthandSchema? {
        val creator = type.companionObject
            ?.members
            ?.filterIsInstance<KFunction<*>>()
            ?.firstOrNull { it.findAnnotation<JsonCreator>()?.mode == JsonCreator.Mode.DELEGATING }
            ?: return null
        val valueType = creator.parameters
            .singleOrNull { it.kind == KParameter.Kind.VALUE }
            ?.type
            ?.classifier as? KClass<*>
            ?: return null
        return ShorthandSchema(kindOf(valueType), enumValuesOf(valueType))
    }

    private fun kindOf(type: KClass<*>?): ArgumentKind = when {
        type == null -> ArgumentKind.OBJECT
        type == YamlElementSelectorUnion::class -> ArgumentKind.SELECTOR
        type.java.isEnum -> ArgumentKind.ENUM
        type == String::class -> ArgumentKind.STRING
        type == Int::class || type == Long::class || type == Double::class || type == Float::class -> ArgumentKind.NUMBER
        type == Boolean::class -> ArgumentKind.BOOLEAN
        type == Any::class -> ArgumentKind.ANY
        Collection::class.java.isAssignableFrom(type.java) || type.java.isArray -> ArgumentKind.ARRAY
        else -> ArgumentKind.OBJECT
    }

    /**
     * The YAML words an enum accepts, taken from each constant's `@JsonProperty` and falling back to
     * the constant name. Null for anything that is not an enum.
     */
    private fun enumValuesOf(type: KClass<*>): List<String>? {
        if (!type.java.isEnum) return null
        return type.java.fields
            .filter { it.isEnumConstant }
            .map { it.getAnnotation(JsonProperty::class.java)?.value ?: it.name }
    }
}
