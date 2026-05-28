package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

data class YamlSetPickerValue(
    val value: String,
    val wheelIndex: Int? = null,
    val waitToSettleTimeoutMs: Int? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(raw: Any): YamlSetPickerValue {
            return when (raw) {
                is String -> YamlSetPickerValue(value = raw)
                is Map<*, *> -> {
                    val value = raw["value"] as? String
                        ?: throw IllegalArgumentException("setPickerValue requires a 'value' field")
                    val wheelIndex = (raw["wheelIndex"] as? Number)?.toInt()
                    val waitToSettleTimeoutMs = (raw["waitToSettleTimeoutMs"] as? Number)?.toInt()
                    val label = raw["label"] as? String
                    val optional = raw["optional"] as? Boolean ?: false
                    YamlSetPickerValue(value, wheelIndex, waitToSettleTimeoutMs, label, optional)
                }
                else -> throw UnsupportedOperationException(
                    "Cannot deserialize setPickerValue with data type ${raw.javaClass}"
                )
            }
        }
    }
}
