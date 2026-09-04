package maestro.orchestra.yaml.schema

/**
 * Names arguments of which the parser needs at least one to be present.
 *
 * [FlowCommandSchema] derives `required` from the Kotlin constructor: an argument is required when
 * Jackson cannot build the object without it. Some commands accept every argument being absent as far
 * as deserialization is concerned and only reject the result later, in `toCommands` — `runFlow` needs
 * a `file` or `commands`, `extendedWaitUntil` needs `visible` or `notVisible`. That rule lives inside
 * a function body, where reflection cannot see it, so the schema would otherwise publish `runFlow: {}`
 * as a valid command. This annotation is the declaration that closes that gap, the way [YamlValues]
 * closes it for a vocabulary the parser checks by hand.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YamlRequiresOneOf(

    /** The argument names, by their YAML spelling. At least one must appear. */
    vararg val names: String,
)
