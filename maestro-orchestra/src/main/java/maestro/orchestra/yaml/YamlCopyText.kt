package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

data class YamlCopyText(
    val text: String,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: Any): YamlCopyText {
            val copyText = when (text) {
                is String -> text
                is Map<*, *> -> {
                    val input = text.getOrDefault("text", "") as String
                    val label = text.getOrDefault("label", null) as String?
                    val optional = text.getOrDefault("optional", false) as? Boolean ?: false
                    return YamlCopyText(input, label, optional)
                }
                is Int, is Long, is Char, is Boolean, is Float, is Double -> text.toString()
                else -> throw UnsupportedOperationException("Cannot deserialize copy text with data type ${text.javaClass}")
            }
            return YamlCopyText(text = copyText)
        }
    }
}
