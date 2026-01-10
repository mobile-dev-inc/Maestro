package maestro.orchestra.yaml

data class YamlCustomCommand(
    val commandName: String,
    val params: Map<String, Any> = emptyMap(),
    val label: String? = null,
    val optional: Boolean = false,
)
