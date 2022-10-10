package maestro.orchestra.yaml

data class YamlRepeatCommand(
    val times: String? = null,
    val commands: List<YamlFluentCommand>
)
