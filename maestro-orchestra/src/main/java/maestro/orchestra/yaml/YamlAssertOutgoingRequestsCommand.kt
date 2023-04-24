package maestro.orchestra.yaml

data class YamlAssertOutgoingRequestsCommand(
    val path: String? = null,
    val headersPresent: List<String> = emptyList(),
    val headersAndValues: Map<String, String> = emptyMap(),
    val httpMethodIs: String? = null,
    val requestBodyContains: String? = null,
)
