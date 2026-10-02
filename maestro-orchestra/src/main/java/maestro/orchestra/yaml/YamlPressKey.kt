package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.KeyCode
import maestro.orchestra.yaml.schema.YamlValues

data class YamlPressKey (
    @YamlValues(KeyCode::class, spelledBy = "description")
    val key: String,
    val label: String? = null,
    val optional: Boolean = false,
){
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(@YamlValues(KeyCode::class, spelledBy = "description") key: String) = YamlPressKey(
            key = key,
        )
    }
}
