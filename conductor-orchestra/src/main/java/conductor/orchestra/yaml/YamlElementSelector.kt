package conductor.orchestra.yaml

data class YamlElementSelector(
    val text: String? = null,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val tolerance: Int? = null,
    val optional: Boolean? = null,
    val retryTapIfNoChange: Boolean? = null
)
