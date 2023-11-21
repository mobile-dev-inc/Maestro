package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlSetLocation @JsonCreator constructor(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)
