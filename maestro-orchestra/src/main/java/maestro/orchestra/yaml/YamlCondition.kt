package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonFormat
import maestro.device.Platform

data class YamlCondition(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
    val platform: Platform? = null,
    val visible: YamlElementSelectorUnion? = null,
    val notVisible: YamlElementSelectorUnion? = null,
    val `true`: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
