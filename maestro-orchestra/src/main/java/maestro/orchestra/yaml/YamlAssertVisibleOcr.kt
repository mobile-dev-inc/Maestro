package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertVisibleOcr(
    val text: String,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): YamlAssertVisibleOcr = YamlAssertVisibleOcr(text = text)
    }
}
