package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlExtractComponentWithAI(
    val image: String,
    val outputVariable: String = "aiOutput",
    val optional: Boolean = true,
    val label: String? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(image: String): YamlExtractComponentWithAI {
            return YamlExtractComponentWithAI(
                image = image,
                optional = true,
            )
        }
    }
}
