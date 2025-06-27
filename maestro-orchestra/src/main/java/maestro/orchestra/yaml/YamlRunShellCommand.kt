package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlRunShellCommand(
    val command: String,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val `when`: YamlCondition? = null,
    val workingDirectory: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
    val outputVariable: String? = null,
    val timeout: Long? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(command: String) = YamlRunShellCommand(
            command = command,
        )
    }
}