package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlExtractTextWithAI(
    val query: String,
    val outputVariable: String = "aiOutput",
    val optional: Boolean = true,
    val label: String? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(query: String): YamlExtractTextWithAI {
            return YamlExtractTextWithAI(
                query = query,
                optional = true,
            )
        }
    }
}