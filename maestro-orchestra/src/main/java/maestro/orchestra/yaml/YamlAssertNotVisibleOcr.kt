package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertNotVisibleOcr(
    val text: String,
    val label: String? = null,
    val optional: Boolean = false,
    val psm: Int? = null,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): YamlAssertNotVisibleOcr = YamlAssertNotVisibleOcr(text = text)
    }
}
