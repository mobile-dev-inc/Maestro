package maestro.networkproxy.yaml

data class YamlResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyFile: String? = null,
)