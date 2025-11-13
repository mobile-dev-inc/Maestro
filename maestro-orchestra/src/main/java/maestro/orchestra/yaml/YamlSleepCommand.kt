package maestro.orchestra.yaml
import com.fasterxml.jackson.annotation.JsonCreator
data class YamlSleepCommand internal constructor(
    val time: Long?,
    val label: String? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromLong(time: Long): YamlSleepCommand {
            return YamlSleepCommand(time, null)
        }

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        fun fromObject(ms: Long?, seconds: Long?, label: String?): YamlSleepCommand {
            val time = ms ?: seconds?.let { it * 1000 }
            return YamlSleepCommand(time, label)
        }
    }
}