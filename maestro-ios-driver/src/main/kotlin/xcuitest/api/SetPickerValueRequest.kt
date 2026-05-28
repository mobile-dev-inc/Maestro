package xcuitest.api

data class SetPickerValueRequest(
    val value: String,
    val wheelIndex: Int?,
    val waitToSettleTimeoutMs: Int?,
    val appIds: Set<String>,
)
