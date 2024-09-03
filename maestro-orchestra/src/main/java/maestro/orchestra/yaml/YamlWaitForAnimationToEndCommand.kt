package maestro.orchestra.yaml

data class YamlWaitForAnimationToEndCommand(
    val timeout: Long?,
    val label: String? = null,
    val optional: Boolean = false,
)
