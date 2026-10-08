package maestro.orchestra.yaml.schema

/**
 * The name one alternative shape of a command is published under.
 *
 * [FlowCommandSchema] would otherwise fall back to the Kotlin class name, putting `YamlSwipeElement`
 * into a document a consumer reads and, through it, into generated docs and error messages. These names
 * are part of the published surface, so they are declared rather than derived.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class YamlVariant(val name: String)
