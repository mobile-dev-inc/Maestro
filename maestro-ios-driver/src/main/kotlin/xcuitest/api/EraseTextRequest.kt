package xcuitest.api

data class EraseTextRequest(
    val charactersToErase: Int,
    val appIds: Set<String>,
)
