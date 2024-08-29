package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertWithAI(
    val assertion: String,
    val optional: Boolean = true,
    val label: String? = null,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(assertion: String): YamlAssertWithAI {
            return YamlAssertWithAI(
                assertion = assertion,
                optional = true,
            )
        }
    }
}
