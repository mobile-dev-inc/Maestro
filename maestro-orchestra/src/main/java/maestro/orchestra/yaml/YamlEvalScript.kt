package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlEvalScript(
    val script: String,
    val label: String? = null,
){
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(script: String) = YamlEvalScript(
            script = script,
        )
    }
}

