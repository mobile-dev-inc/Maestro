package maestro.orchestra.yaml
import com.fasterxml.jackson.annotation.JsonCreator
data class YamlSleepCommand(
    val time: Long?,
    val label: String? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun create(time: Long): YamlSleepCommand {
            return YamlSleepCommand(time = time)
        }
    }
}