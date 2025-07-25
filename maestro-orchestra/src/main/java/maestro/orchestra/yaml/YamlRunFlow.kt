package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlRunFlow(
    val file: String? = null,
    val `when`: YamlCondition? = null,
    val env: Map<String, String> = emptyMap(),
    val commands: List<YamlFluentCommand>? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(file: String) = YamlRunFlow(
            file = file,
        )
    }
}
