package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlExtractPointWithAI(
    val query: String,
    val outputVariable: String = "aiOutput",
    val passes: Int = 1,
    val optional: Boolean = true,
    val label: String? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(query: String): YamlExtractPointWithAI {
            return YamlExtractPointWithAI(
                query = query,
                optional = true,
            )
        }
    }
}