package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

data class YamlInputText(
    val text: String,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: Any): YamlInputText {
            val inputText = when (text) {
                is String -> text
                is Map<*, *> -> {
                    val input = text.getOrDefault("text", "") as String
                    val label = text.getOrDefault("label", null) as String?
                    return YamlInputText(input, label)
                }
                is Int, is Long, is Char, is Boolean, is Float, is Double -> text.toString()
                else -> throw UnsupportedOperationException("Cannot deserialize input text with data type ${text.javaClass}")
            }
            return YamlInputText(text = inputText)
        }
    }
}
