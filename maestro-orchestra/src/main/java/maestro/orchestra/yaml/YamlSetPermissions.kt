package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonAlias

data class YamlSetPermissions(
    @JsonAlias("url")
    val appId: String?,
    val permissions: Map<String, String>,
    val label: String? = null,
    val optional: Boolean = false,
)