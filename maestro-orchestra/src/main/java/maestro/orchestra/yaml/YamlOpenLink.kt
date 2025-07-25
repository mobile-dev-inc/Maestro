package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlOpenLink(
    val link: String,
    val browser: Boolean = false,
    val autoVerify: Boolean = false,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(link: String): YamlOpenLink {
            return YamlOpenLink(
                link = link,
            )
        }
    }
}
