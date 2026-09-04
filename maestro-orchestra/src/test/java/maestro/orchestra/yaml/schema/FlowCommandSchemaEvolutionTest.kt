package maestro.orchestra.yaml.schema

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlElementSelectorUnion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths
import kotlin.reflect.KClass

/**
 * Every other test in this package reads its expectations off the `Yaml*` types that exist **today**,
 * so none of them can answer the question that decides whether the schema is safe to publish: what
 * happens the next time somebody changes one?
 *
 * These drive [FlowCommandSchema.schemaOf] with synthetic `Yaml*` shapes instead — a command being
 * added, an argument being renamed, an argument being retyped — and assert the derived schema says
 * what the parser would actually accept for them.
 */
class FlowCommandSchemaEvolutionTest {

    private val flowPath = Paths.get("test.yaml")

    // ------------------------------------------------------------------- adding a command

    /** A command written `- newBare` and nothing else, like `back`. */
    data class YamlNewBare(
        val label: String? = null,
        val optional: Boolean = false,
    )

    data class YamlNewNested(val inner: String, val depth: Int = 0)

    /** A new command carrying one argument of each data type the parser can see. */
    data class YamlNewMixedTypes(
        val text: String,
        val count: Int,
        val big: Long,
        val ratio: Double,
        val flag: Boolean,
        val items: List<String> = emptyList(),
        val mapping: Map<String, String> = emptyMap(),
        val anything: Any? = null,
        val target: YamlElementSelectorUnion? = null,
        val nested: YamlNewNested? = null,
        val label: String? = null,
        val optional: Boolean = false,
    )

    enum class WireNamed {
        @JsonProperty("fast") FAST,
        @JsonProperty("slow") SLOW,
    }

    enum class PlainNamed { ALPHA, BETA }

    enum class Described(val description: String) {
        A("Letter A"),
        B("Letter B"),
    }

    /** A new command carrying enum-valued arguments in each of the three forms the schema supports. */
    data class YamlNewEnums(
        val speed: WireNamed,
        val mode: PlainNamed? = null,
        @YamlValues(of = Described::class, spelledBy = "description") val letter: String,
        @YamlValues(of = PlainNamed::class) val plain: String = "ALPHA",
        val speeds: List<WireNamed> = emptyList(),
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** A new command with a single-value form, like `setDarkMode: enabled`. */
    data class YamlNewShorthand(
        val speed: WireNamed,
        val label: String? = null,
        val optional: Boolean = false,
    ) {
        companion object {
            @JvmStatic
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            fun parse(speed: WireNamed) = YamlNewShorthand(speed)
        }
    }

    @Test
    fun `a command with no arguments is described as taking none`() {
        val schema = FlowCommandSchema.schemaOf("newBare", YamlNewBare::class)

        assertThat(schema.name).isEqualTo("newBare")
        assertThat(schema.selector).isFalse()
        assertThat(schema.arguments).isEmpty()
        assertThat(schema.variants).isEmpty()
        assertThat(schema.shorthand).isNull()
    }

    @Test
    fun `a command whose whole value is a plain string is described as a string shorthand`() {
        val schema = FlowCommandSchema.schemaOf("newPlainString", String::class)

        assertThat(schema.shorthand).isEqualTo(ShorthandSchema(ArgumentKind.STRING, null))
        assertThat(schema.arguments).isEmpty()
        assertThat(schema.selector).isFalse()
    }

    @Test
    fun `each argument data type is described by its own kind`() {
        val arguments = FlowCommandSchema.schemaOf("newMixed", YamlNewMixedTypes::class)
            .arguments.associateBy { it.name }

        assertThat(arguments.keys).containsExactly(
            "text", "count", "big", "ratio", "flag", "items", "mapping", "anything", "target", "nested",
        )
        assertThat(arguments.getValue("text")).isEqualTo(argument("text", ArgumentKind.STRING, required = true))
        assertThat(arguments.getValue("count")).isEqualTo(argument("count", ArgumentKind.NUMBER, required = true))
        assertThat(arguments.getValue("big")).isEqualTo(argument("big", ArgumentKind.NUMBER, required = true))
        assertThat(arguments.getValue("ratio")).isEqualTo(argument("ratio", ArgumentKind.NUMBER, required = true))
        assertThat(arguments.getValue("flag")).isEqualTo(argument("flag", ArgumentKind.BOOLEAN, required = true))
        assertThat(arguments.getValue("items")).isEqualTo(argument("items", ArgumentKind.ARRAY, required = false))
        assertThat(arguments.getValue("anything")).isEqualTo(argument("anything", ArgumentKind.ANY, required = false))
        assertThat(arguments.getValue("target")).isEqualTo(argument("target", ArgumentKind.SELECTOR, required = false))

        // A map and a nested object both flatten to OBJECT: the schema does not describe what may go
        // inside either. Pinned so that stops being a surprise if a command starts relying on it.
        assertThat(arguments.getValue("mapping").kind).isEqualTo(ArgumentKind.OBJECT)
        assertThat(arguments.getValue("nested").kind).isEqualTo(ArgumentKind.OBJECT)
    }

    @Test
    fun `each way of declaring an enum vocabulary is described as an ENUM`() {
        val arguments = FlowCommandSchema.schemaOf("newEnums", YamlNewEnums::class)
            .arguments.associateBy { it.name }

        // An enum-typed argument: the words are the constants' @JsonProperty wire names.
        assertThat(arguments.getValue("speed"))
            .isEqualTo(argument("speed", ArgumentKind.ENUM, required = true, values = listOf("fast", "slow")))
        // ... falling back to the constant names when there is no @JsonProperty.
        assertThat(arguments.getValue("mode"))
            .isEqualTo(argument("mode", ArgumentKind.ENUM, required = false, values = listOf("ALPHA", "BETA")))
        // A String argument that names its vocabulary with @YamlValues, spelled by a property ...
        assertThat(arguments.getValue("letter"))
            .isEqualTo(argument("letter", ArgumentKind.ENUM, required = true, values = listOf("Letter A", "Letter B")))
        // ... and spelled by the constant names.
        assertThat(arguments.getValue("plain"))
            .isEqualTo(argument("plain", ArgumentKind.ENUM, required = false, values = listOf("ALPHA", "BETA")))

        // A list of enums keeps neither the element type nor the vocabulary.
        assertThat(arguments.getValue("speeds")).isEqualTo(argument("speeds", ArgumentKind.ARRAY, required = false))
    }

    @Test
    fun `a delegating creator is described as a shorthand carrying its vocabulary`() {
        val schema = FlowCommandSchema.schemaOf("newShorthand", YamlNewShorthand::class)

        assertThat(schema.shorthand).isEqualTo(ShorthandSchema(ArgumentKind.ENUM, listOf("fast", "slow")))
    }

    // -------------------------------------------------------------- modifying a command

    data class YamlRenameBefore(
        val text: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** The Kotlin parameter was renamed; the YAML spelling was deliberately kept. */
    data class YamlRenameAfter(
        @JsonProperty("text") val message: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** The new spelling is the parameter name; the old one stays accepted as an alias. */
    data class YamlRenameWithAlias(
        @JsonAlias("text") val message: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** The same rename, written with Kotlin's `@field:` use-site target instead of the default. */
    data class YamlRenameOnField(
        @field:JsonProperty("text") val message: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** And with `@get:`. */
    data class YamlRenameOnGetter(
        @get:JsonProperty("text") val message: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** An alias written with a use-site target. */
    data class YamlAliasOnField(
        @field:JsonAlias("text") val message: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    data class YamlRetypeBefore(
        val amount: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    data class YamlRetypeToNumber(
        val amount: Int,
        val label: String? = null,
        val optional: Boolean = false,
    )

    data class YamlRetypeToEnum(
        val amount: WireNamed,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** `String` -> `String?`: the same kind, but no longer mandatory. */
    data class YamlRetypeToNullable(
        val amount: String?,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /** `spelledBy` names a property the enum does not have. */
    data class YamlVocabularyMisspelled(
        @YamlValues(of = Described::class, spelledBy = "caption") val letter: String,
        val label: String? = null,
        val optional: Boolean = false,
    )

    /**
     * Renaming a Kotlin parameter while keeping its YAML spelling is the ordinary way to rename a
     * field without breaking flows. Jackson keys off `@JsonProperty`, so the schema has to as well —
     * otherwise it starts advertising a key the parser rejects as an unknown property.
     */
    @Test
    fun `an argument renamed behind its YAML spelling keeps advertising the YAML spelling`() {
        assertThat(names(YamlRenameBefore::class)).containsExactly("text")
        assertThat(names(YamlRenameAfter::class)).containsExactly("text")
    }

    /** The same rename, against Jackson itself: `text` is what is read, `message` is rejected. */
    @Test
    fun `Jackson reads a renamed argument by its YAML spelling and not by the parameter name`() {
        val mapper = jacksonObjectMapper()

        assertThat(mapper.readValue("""{"text":"hello"}""", YamlRenameAfter::class.java).message)
            .isEqualTo("hello")
        assertThrows<Exception> { mapper.readValue("""{"message":"hello"}""", YamlRenameAfter::class.java) }
    }

    /**
     * `@JsonAlias` is a spelling the parser accepts and the schema currently never mentions.
     * `launchApp: {url: …}` is the documented form for web flows and parses today.
     */
    @Test
    fun `an argument's aliases are advertised alongside its name`() {
        val renamed = FlowCommandSchema.schemaOf("modified", YamlRenameWithAlias::class).arguments.single()
        assertThat(renamed.name).isEqualTo("message")
        assertThat(renamed.aliases).containsExactly("text")

        // Against the real tree: `launchApp: {url: ...}` is the documented form for web flows.
        MaestroFlowParser.parseCommand(flowPath, APP_ID, "launchApp:\n  url: https://example.com")
        val appId = FlowCommandSchema.commands().single { it.name == "launchApp" }
            .arguments.single { it.name == "appId" }
        assertThat(appId.aliases).containsExactly("url")
    }

    /**
     * Kotlin's default target for these annotations is the value parameter, but `@field:` and `@get:` are
     * legal and Jackson binds by them just the same. Reading only the parameter would leave the schema
     * advertising the name the rename moved away from -- and Kotlin has warned that the default target is
     * itself due to change, which would make that the common case rather than the unusual one.
     */
    @Test
    fun `a rename written with a use-site target is seen too`() {
        val mapper = jacksonObjectMapper()

        // What Jackson actually binds, so the assertions below are not just describing the implementation.
        assertThat(mapper.readValue("""{"text":"hello"}""", YamlRenameOnField::class.java).message).isEqualTo("hello")
        assertThrows<Exception> { mapper.readValue("""{"message":"hello"}""", YamlRenameOnField::class.java) }
        assertThat(mapper.readValue("""{"text":"hello"}""", YamlRenameOnGetter::class.java).message).isEqualTo("hello")
        assertThat(mapper.readValue("""{"text":"hello"}""", YamlAliasOnField::class.java).message).isEqualTo("hello")
        assertThat(mapper.readValue("""{"message":"hello"}""", YamlAliasOnField::class.java).message).isEqualTo("hello")

        assertThat(names(YamlRenameOnField::class)).containsExactly("text")
        assertThat(names(YamlRenameOnGetter::class)).containsExactly("text")

        val aliased = FlowCommandSchema.schemaOf("modified", YamlAliasOnField::class).arguments.single()
        assertThat(aliased.name).isEqualTo("message")
        assertThat(aliased.aliases).containsExactly("text")
    }

    @Test
    fun `retyping an argument changes the kind it is described by`() {
        fun kindOf(type: KClass<*>) = FlowCommandSchema.schemaOf("retyped", type).arguments.single().kind

        assertThat(kindOf(YamlRetypeBefore::class)).isEqualTo(ArgumentKind.STRING)
        assertThat(kindOf(YamlRetypeToNumber::class)).isEqualTo(ArgumentKind.NUMBER)
        assertThat(kindOf(YamlRetypeToEnum::class)).isEqualTo(ArgumentKind.ENUM)
        assertThat(FlowCommandSchema.schemaOf("retyped", YamlRetypeToEnum::class).arguments.single().values)
            .containsExactly("fast", "slow")
    }

    @Test
    fun `making an argument nullable stops it being required`() {
        assertThat(FlowCommandSchema.schemaOf("retyped", YamlRetypeBefore::class).arguments.single().required)
            .isTrue()
        assertThat(FlowCommandSchema.schemaOf("retyped", YamlRetypeToNullable::class).arguments.single().required)
            .isFalse()
    }

    /**
     * A `spelledBy` that no longer matches — because the property was renamed — must say so. It is
     * reached from every `commands()` call, so the failure is the whole schema, and the message is
     * the only thing pointing at which annotation is wrong.
     */
    @Test
    fun `a vocabulary spelled by a property the enum does not have says which annotation is wrong`() {
        val thrown = assertThrows<Exception> {
            FlowCommandSchema.schemaOf("vocab", YamlVocabularyMisspelled::class)
        }

        assertThat(thrown).hasMessageThat().contains("spelledBy = \"caption\"")
        assertThat(thrown).hasMessageThat().contains("Described")
    }

    // ------------------------------------------------------------------------- plumbing

    private fun names(type: KClass<*>) =
        FlowCommandSchema.schemaOf("modified", type).arguments.map { it.name }

    private fun argument(
        name: String,
        kind: ArgumentKind,
        required: Boolean,
        values: List<String>? = null,
    ) = ArgumentSchema(name, kind, required, values)

    private companion object {
        const val APP_ID = "com.example.app"
    }
}
