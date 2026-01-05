package maestro.orchestra.yaml
import com.fasterxml.jackson.annotation.JsonCreator

data class YamlSleepCommand(
    val time: String?,
    val label: String? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: String): YamlSleepCommand {
            return YamlSleepCommand(value, null)
        }
    }
}