package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlPressKey (
    val key: String,
    val label: String? = null,
){
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(key: String) = YamlPressKey(
            key = key,
        )
    }
}