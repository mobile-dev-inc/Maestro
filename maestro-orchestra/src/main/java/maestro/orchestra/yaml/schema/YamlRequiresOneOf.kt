package maestro.orchestra.yaml.schema

/**
 * Names arguments the parser enforces a rule across: at least one must be present, and with
 * [exclusive], no more than one.
 *
 * [FlowCommandSchema] derives `required` from the Kotlin constructor: an argument is required when
 * Jackson cannot build the object without it. Some commands accept every argument being absent as far
 * as deserialization is concerned and only reject the result later, in `toCommands` — `runFlow` needs
 * a `file` or `commands` and refuses both, `extendedWaitUntil` needs `visible` or `notVisible` and
 * accepts both. Those rules live inside a function body, where reflection cannot see them, so the
 * schema would otherwise publish `runFlow: {}` as a valid command. This annotation is the declaration
 * that closes that gap, the way [YamlValues] closes it for a vocabulary the parser checks by hand.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YamlRequiresOneOf(

    /** The argument names, by their YAML spelling. At least one must appear. */
    vararg val names: String,

    /** Whether the parser also rejects more than one of [names] being present. */
    val exclusive: Boolean = false,
)
