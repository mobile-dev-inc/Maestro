package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlPressKey (
    val key: String? = null,
    val keys: List<String>? = null,
    val label: String? = null,
    val optional: Boolean = false,
){
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(key: String) = YamlPressKey(
            key = key,
        )
        
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(keys: List<String>) = YamlPressKey(
            keys = keys,
        )
    }
}
