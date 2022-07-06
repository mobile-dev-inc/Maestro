package conductor.orchestra.yaml

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize(`as` = YamlElementSelector::class)
data class YamlElementSelector(
    val text: String? = null,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val tolerance: Int? = null,
    val optional: Boolean? = null,
    val retryTapIfNoChange: Boolean? = null
) : YamlElementSelectorUnion
