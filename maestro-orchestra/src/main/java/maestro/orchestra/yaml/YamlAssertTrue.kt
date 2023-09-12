package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertTrue(
    val condition: String? = null,
    val label: String? = null,
){
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(condition: String) = YamlAssertTrue(
            condition = condition,
        )
    }
}

