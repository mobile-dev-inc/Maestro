package maestro.orchestra.yaml.schema

import kotlin.reflect.KClass

/**
 * Names the closed vocabulary a `String`-typed YAML field is validated against, so [FlowCommandSchema]
 * can report the field as [ArgumentKind.ENUM] with those words.
 *
 * Some fields have to stay `String` even though the parser only accepts a fixed set of words, because
 * they also accept `${VAR}` interpolation — `pressKey` and `setOrientation` both do. Typing them as the
 * enum would break interpolation; leaving the vocabulary only inside the parser's `getByName` lookup
 * would leave the schema blind to it. This annotation is the declaration that closes that gap.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class YamlValues(

    /** The enum the parser looks the field's value up in. */
    val of: KClass<out Enum<*>>,

    /**
     * The property holding each constant's YAML spelling, when that is neither the constant's
     * `@JsonProperty` wire name nor its name. `KeyCode` is written `Volume Up`, not `VOLUME_UP`, and
     * cannot say so with `@JsonProperty` because Jackson also serializes it as `PressKeyCommand.code`,
     * where the constant name is the wire name. Empty means the usual rule applies.
     */
    val spelledBy: String = "",
)
