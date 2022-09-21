package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlRunFlow(
    val path: String,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(path: String) = YamlRunFlow(
            path = path,
        )
    }
}
