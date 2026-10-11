package maestro.orchestra.yaml

import maestro.orchestra.yaml.schema.YamlRequiresOneOf

// The order is the order a consumer should prefer, and the schema publishes it verbatim: `retry` has
// no `retry: "path"` shorthand, so an inline block is the common way to write it. `runFlow` lists
// `file` first for the opposite reason -- it does have that shorthand. Reordering either changes
// what a consumer seeds, so it is a deliberate choice rather than declaration order.
@YamlRequiresOneOf("commands", "file", exclusive = true)
data class YamlRetryCommand(
    val maxRetries: String? = null,
    val file: String? = null,
    val commands: List<YamlFluentCommand>? = null,
    val env: Map<String, String> = emptyMap(),
    val label: String? = null,
    val optional: Boolean = false,
)