package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAddMedia(
    val files: List<String?>? = null,
) {
    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(files: List<String>) = YamlAddMedia(
            files = files,
        )
    }
}