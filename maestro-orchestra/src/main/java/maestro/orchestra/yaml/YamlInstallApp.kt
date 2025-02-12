package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlInstallApp(
    val path: String,
    val label: String? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(path: String) = YamlInstallApp(
            path = path,
        )
    }
}
