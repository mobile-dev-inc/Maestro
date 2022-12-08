package maestro.networkproxy.yaml

import com.fasterxml.jackson.annotation.JsonIgnore

data class YamlMappingRule(
    val path: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val response: YamlResponse,
    @JsonIgnore val ruleFilePath: String? = null,
)