package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.orchestra.yaml.YamlElementSelector
import maestro.orchestra.yaml.YamlElementSelectorUnion
import maestro.orchestra.yaml.YamlFluentCommand
import maestro.orchestra.yaml.stringCommands
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

/** How a YAML value is written. */
enum class ArgumentKind {
    STRING,
    NUMBER,
    BOOLEAN,

    /** A fixed vocabulary; the accepted words are in [ArgumentSchema.values]. */
    ENUM,

    /** A list of values. */
    ARRAY,

    /** An element selector — a string, or a map of [FlowCommandSchema.selectorArguments]. */
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
    /**
     * Other spellings the parser also accepts for this argument, from `@JsonAlias`. Null when there are
     * none. [name] is the spelling to write; these are the ones a consumer must not reject.
     */
    val aliases: List<String>? = null,
)

/** One of the alternative shapes a command accepts, e.g. `swipe` by direction vs. by coordinates. */
data class VariantSchema(
    val name: String,
    val arguments: List<ArgumentSchema>,
)

/**
 * A rule the parser enforces across several of a command's arguments, from [YamlRequiresOneOf]. The
 * arguments are individually [ArgumentSchema.required]`= false` — each may be omitted, but not all of
 * them at once, and when [exclusive] not more than one of them at a time.
 */
data class OneOfSchema(
    val names: List<String>,
    val exclusive: Boolean,
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

    /** The command's one-of rule, or null when it declares none. */
    val requiredOneOf: OneOfSchema? = null,
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

    /**
     * The shape of the document [asJson] produces, not the command surface it describes. Bumped when a
     * field is added or renamed here, so a consumer diffing published JSON can tell a schema-format
     * change from Maestro gaining a command.
     */
    const val VERSION = 1


    /** Arguments every command inherits. Consumers render these once rather than per command. */
    val commonArguments: List<ArgumentSchema> = listOf(
        ArgumentSchema("label", ArgumentKind.STRING, required = false),
        ArgumentSchema("optional", ArgumentKind.BOOLEAN, required = false),
    )

    private val commonArgumentNames = commonArguments.map { it.name }.toSet()

    /**
     * The arguments an [ArgumentKind.SELECTOR] value accepts in its map form. `tapOn`, `assertVisible`
     * and their siblings take one of these instead of named arguments of their own, so a consumer that
     * only reads [CommandSchema.arguments] sees nothing at all for the most-used commands in Maestro.
     * Rendered once here rather than repeated under every selector command.
     */
    val selectorArguments: List<ArgumentSchema> by lazy { argumentsOf(YamlElementSelector::class) }

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
            "version" to VERSION,
            "commonArguments" to commonArguments,
            "selectorArguments" to selectorArguments,
            "commands" to commands(),
        )
        return ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(document)
    }

    /**
     * Derives one command from the type [YamlFluentCommand] declares for it. Visible to tests so the
     * derivation can be driven with a `Yaml*` shape that does not exist yet -- the only way to ask
     * what happens when a command is added or an argument renamed or retyped.
     */
    internal fun schemaOf(name: String, type: KClass<*>): CommandSchema {
        val bareString = name in stringCommands
        val kind = kindOf(type)

        if (kind == ArgumentKind.SELECTOR) {
            return CommandSchema(name, selector = true, bareString, null, emptyList(), emptyList())
        }

        // A command whose value is a plain scalar, e.g. `openLink: https://example.com`.
        if (kind != ArgumentKind.OBJECT) {
            val shorthand = ShorthandSchema(kind, enumValuesOf(type))
            return CommandSchema(name, selector = false, bareString, shorthand, emptyList(), emptyList())
        }

        val subclasses = type.sealedSubclasses
        val shorthand = shorthandOf(type)
        if (subclasses.isEmpty()) {
            return CommandSchema(name, false, bareString, shorthand, argumentsOf(type), emptyList(), requiredOneOf(type))
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
            // sealedSubclasses has no documented order, so name them in one, or a compiler upgrade
            // silently reorders every published document.
            variants = perVariant.map { (subclass, arguments) ->
                VariantSchema(subclass.simpleName!!, arguments - shared.toSet())
            }.sortedBy { it.name },
            requiredOneOf = requiredOneOf(type),
        )
    }

    private fun argumentsOf(type: KClass<*>): List<ArgumentSchema> {
        return type.primaryConstructor?.parameters.orEmpty()
            .mapNotNull { parameter ->
                val name = wireNameOf(parameter, type)?.takeUnless { it in commonArgumentNames } ?: return@mapNotNull null
                val argumentType = parameter.type.classifier as? KClass<*>
                val values = valuesOf(parameter, argumentType)
                ArgumentSchema(
                    name = name,
                    kind = if (values != null) ArgumentKind.ENUM else kindOf(argumentType),
                    // Required in YAML means the parser cannot fill it in: no Kotlin default AND not
                    // nullable. A nullable parameter without a default still deserializes when the key
                    // is absent, because Jackson supplies null -- `- launchApp` alone is valid YAML.
                    required = !parameter.isOptional && !parameter.type.isMarkedNullable,
                    values = values,
                    aliases = annotationOf(JsonAlias::class, parameter, type)?.value?.toList()?.ifEmpty { null },
                )
            }
    }

    /** The one-of rule [type] declares, or null when it declares none. */
    private fun requiredOneOf(type: KClass<*>): OneOfSchema? {
        val declared = type.findAnnotation<YamlRequiresOneOf>() ?: return null
        return declared.names.toList().ifEmpty { null }?.let { OneOfSchema(it, declared.exclusive) }
    }

    /**
     * The key the parser reads this argument from. Jackson keys off `@JsonProperty` when there is one and
     * only falls back to the Kotlin parameter name, so the schema has to do the same -- otherwise renaming
     * a parameter while keeping its YAML spelling makes the schema advertise a key the parser rejects as
     * an unknown property. An empty value means `@JsonProperty` without a name, which is Jackson's own
     * "use the default" and leaves the parameter name in place.
     */
    private fun wireNameOf(parameter: KParameter, type: KClass<*>): String? =
        annotationOf(JsonProperty::class, parameter, type)?.value?.takeUnless { it.isEmpty() }
            ?: parameter.name

    /**
     * [annotation] as it applies to [parameter], wherever it was written. Kotlin's default target for these
     * is the value parameter, but `@field:` and `@get:` are legal and Jackson honours them just the same --
     * reading only the parameter would leave the schema advertising the name the rename moved away from.
     */
    private fun <A : Annotation> annotationOf(annotation: KClass<A>, parameter: KParameter, type: KClass<*>): A? {
        parameter.annotations.filterIsInstance(annotation.java).firstOrNull()?.let { return it }
        val property = type.memberProperties.firstOrNull { it.name == parameter.name } ?: return null
        return property.annotations.filterIsInstance(annotation.java).firstOrNull()
            ?: property.getter.annotations.filterIsInstance(annotation.java).firstOrNull()
            ?: property.javaField?.getAnnotation(annotation.java)
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
        val parameter = creator.parameters.singleOrNull { it.kind == KParameter.Kind.VALUE } ?: return null
        val valueType = parameter.type.classifier as? KClass<*> ?: return null
        val values = valuesOf(parameter, valueType)
        return ShorthandSchema(if (values != null) ArgumentKind.ENUM else kindOf(valueType), values)
    }

    /**
     * The vocabulary a value accepts: the constants of its own type when that is an enum, or of the enum
     * a `String`-typed value names with [YamlValues] because it has to stay a `String` to keep accepting
     * `${VAR}`. Null when the value has no closed vocabulary.
     */
    private fun valuesOf(parameter: KParameter, type: KClass<*>?): List<String>? {
        val declared = parameter.findAnnotation<YamlValues>() ?: return type?.let(::enumValuesOf)
        return enumValuesOf(declared.of, declared.spelledBy)
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
     * The YAML words an enum accepts, taken from each constant's `@JsonProperty` and falling back to the
     * constant name — or, when [spelledBy] names one, read off that property of each constant instead.
     * Null for anything that is not an enum.
     */
    private fun enumValuesOf(type: KClass<*>, spelledBy: String = ""): List<String>? {
        if (!type.java.isEnum) return null
        if (spelledBy.isNotEmpty()) {
            val spelling = type.memberProperties.singleOrNull { it.name == spelledBy }
                ?: error(
                    "@YamlValues(of = ${type.simpleName}::class, spelledBy = \"$spelledBy\") names a property " +
                        "${type.simpleName} does not have. Available: ${type.memberProperties.map { it.name }.sorted()}"
                )
            return type.java.enumConstants.map { spelling.getter.call(it).toString() }
        }
        // enumConstants is declaration order; getFields() is unspecified, so both paths use the former.
        return type.java.enumConstants.map { constant ->
            val name = (constant as Enum<*>).name
            type.java.getField(name).getAnnotation(JsonProperty::class.java)?.value ?: name
        }
    }
}
