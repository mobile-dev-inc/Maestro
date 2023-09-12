package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlInputText(
    val text: String,
    val label: String? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String) = YamlInputText(
            text = text,
        )
    }
}
