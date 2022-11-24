package maestro.orchestra.yaml

data class YamlCondition(
    val visible: YamlElementSelectorUnion? = null,
    val notVisible: YamlElementSelectorUnion? = null,
    val `true`: String? = null,
)
