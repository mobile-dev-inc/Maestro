package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.orchestra.BiometryResult

data class YamlBiometry(
    val result: BiometryResult,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: String): YamlBiometry {
            val result = when (value.lowercase()) {
                "match" -> BiometryResult.Match
                "nomatch", "noMatch", "no_match", "no-match", "notmatch" -> BiometryResult.NoMatch
                else -> BiometryResult.Match
            }
            return YamlBiometry(result = result)
        }
    }
}


