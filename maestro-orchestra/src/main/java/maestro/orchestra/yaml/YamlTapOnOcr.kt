package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlTapOnOcr(
    val text: String,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): YamlTapOnOcr = YamlTapOnOcr(text = text)
    }
}
