package maestro.orchestra.yaml

data class YamlWaitForAnimationToEndCommand(
    val timeout: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
