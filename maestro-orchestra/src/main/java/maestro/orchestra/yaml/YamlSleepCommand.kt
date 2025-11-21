package maestro.orchestra.yaml
import com.fasterxml.jackson.annotation.JsonCreator

data class YamlSleepCommand internal constructor(
    val time: Long?,
    val label: String? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromValue(value: Any): YamlSleepCommand {
            val time = when (value) {
                is Int -> value.toLong()
                is Long -> value
                is String -> parseDuration(value)
                else -> throw IllegalArgumentException("Sleep value must be a duration string or a number of milliseconds")
            }
            return YamlSleepCommand(time, null)
        }

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        fun fromObject(duration: String?, ms: Long?, seconds: Long?, label: String?): YamlSleepCommand {
            val time = when {
                duration != null -> parseDuration(duration)
                ms != null -> ms
                seconds != null -> seconds * 1000
                else -> null
            }
            return YamlSleepCommand(time, label)
        }

        private fun parseDuration(value: String): Long {
            val trimmed = value.trim().lowercase()
            return when {
                trimmed.endsWith("ms") -> trimmed.removeSuffix("ms").toLong()
                trimmed.endsWith("s") -> trimmed.removeSuffix("s").toLong() * 1000
                else -> trimmed.toLong() // Assume ms for backward compatibility
            }
        }
    }
}