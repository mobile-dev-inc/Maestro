package maestro.orchestra.yaml

data class YamlRetryCommand(
    val maxRetries: String? = null,
    val file: String? = null,
    val commands: List<YamlFluentCommand>? = null,
    val env: Map<String, String> = emptyMap(),
    val label: String? = null,
    val optional: Boolean = false,
)