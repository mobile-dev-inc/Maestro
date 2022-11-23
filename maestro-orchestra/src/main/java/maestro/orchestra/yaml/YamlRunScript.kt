package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlRunScript(
    val file: String,
    val env: Map<String, String> = emptyMap(),  // TODO
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(file: String) = YamlRunScript(
            file = file,
        )
    }
}
