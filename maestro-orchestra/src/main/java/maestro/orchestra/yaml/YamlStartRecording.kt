package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlStartRecording(
    val path: String
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

class YamlStopRecording
