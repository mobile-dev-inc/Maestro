package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlStartRecording(
    val path: String,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(path: String): YamlStartRecording {
            return YamlStartRecording(
                path = path,
            )
        }
    }
}

data class YamlStopRecording(
    val label: String? = null,
    val optional: Boolean = false,
)
