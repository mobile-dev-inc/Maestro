package maestro.orchestra.yaml

import maestro.orchestra.yaml.schema.YamlRequiresOneOf

import com.fasterxml.jackson.annotation.JsonCreator

@YamlRequiresOneOf("file", "commands", exclusive = true)
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
