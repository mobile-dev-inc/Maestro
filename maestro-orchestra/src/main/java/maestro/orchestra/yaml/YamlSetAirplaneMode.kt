package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.orchestra.AirplaneValue

data class YamlSetAirplaneMode(
    val value: AirplaneValue,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: AirplaneValue): YamlSetAirplaneMode {
            return YamlSetAirplaneMode(value)
        }
    }
}
