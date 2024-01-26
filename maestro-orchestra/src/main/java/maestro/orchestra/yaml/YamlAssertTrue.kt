package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertTrue(
    val condition: String? = null,
    val label: String? = null,
){
    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(condition: Any): YamlAssertTrue {
            val evaluatedCondition = when (condition) {
                is String -> condition
                is Int, is Long, is Char, is Boolean, is Float, is Double -> condition.toString()
                is Map<*, *> -> {
                    val evaluatedCondition = condition.getOrDefault("condition", "") as String
                    val label = condition.getOrDefault("label", "") as String
                    return YamlAssertTrue(evaluatedCondition, label)
                }
                else -> throw UnsupportedOperationException("Cannot deserialize assert true with data type ${condition.javaClass}")
            }
            return YamlAssertTrue(
                condition = evaluatedCondition,
            )
        }
    }
}

