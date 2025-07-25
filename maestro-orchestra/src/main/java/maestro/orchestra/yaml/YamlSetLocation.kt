package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlSetLocation @JsonCreator constructor(
    val latitude: String,
    val longitude: String,
    val label: String? = null,
    val optional: Boolean = false,
)
