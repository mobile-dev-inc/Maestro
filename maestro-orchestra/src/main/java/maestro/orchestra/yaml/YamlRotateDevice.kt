package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlRotateDevice(
    val direction: String? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(rotateDirection: String?) = YamlRotateDevice(
            direction = when (rotateDirection?.lowercase()) {
                "left" -> "Left"
                "right" -> "Right"
                else -> "Right"
            }
        )
    }
}
