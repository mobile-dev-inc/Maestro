package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlShake(
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse() = YamlShake()

    }

}
