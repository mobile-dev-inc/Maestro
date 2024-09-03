package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

data class YamlEvalScript(
    val script: String,
    val label: String? = null,
    val optional: Boolean = false,
){
    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(script: Any): YamlEvalScript {
            val evalScript = when (script) {
                is String -> script
                is Map<*, *> -> {
                    val evaluatedScript = script.getOrDefault("script", "") as String
                    val label = script.getOrDefault("label", null) as String?
                    return YamlEvalScript(evaluatedScript, label)
                }
                is Int, is Long, is Char, is Boolean, is Float, is Double -> script.toString()
                else -> throw UnsupportedOperationException("Cannot deserialize evaluate script with data type ${script.javaClass}")
            }
            return YamlEvalScript(
                script = evalScript,
            )
        }
    }
}
