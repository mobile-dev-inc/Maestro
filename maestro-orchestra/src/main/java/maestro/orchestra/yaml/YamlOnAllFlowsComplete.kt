package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlOnAllFlowsComplete(val commands: List<YamlFluentCommand>) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(commands: List<YamlFluentCommand>) = YamlOnAllFlowsComplete(
            commands = commands
        )
    }
}
