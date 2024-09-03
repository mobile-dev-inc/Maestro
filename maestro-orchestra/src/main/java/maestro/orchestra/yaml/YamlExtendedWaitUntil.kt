package maestro.orchestra.yaml

data class YamlExtendedWaitUntil(
    val visible: YamlElementSelectorUnion? = null,
    val notVisible: YamlElementSelectorUnion? = null,
    val timeout: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
