package maestro.orchestra.yaml

data class YamlRepeatCommand(
    val times: String? = null,
    val `while`: YamlCondition? = null,
    val commands: List<YamlFluentCommand>,
    val timeout: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
