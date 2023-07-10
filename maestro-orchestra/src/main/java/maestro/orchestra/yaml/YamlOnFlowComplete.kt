package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlOnFlowComplete(val commands: List<YamlFluentCommand>) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(commands: List<YamlFluentCommand>) = YamlOnFlowComplete(
            commands = commands
        )
    }
}