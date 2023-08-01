package xcuitest.api

data class InputTextRequest(
    val text: String,
    val appIds: Set<String>
)
