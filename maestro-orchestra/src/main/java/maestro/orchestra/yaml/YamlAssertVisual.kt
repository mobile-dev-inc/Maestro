package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

private const val DEFAULT_DIFF_THRESHOLD = 95

data class YamlAssertVisual(
    val baseline: String,
    val thresholdPercentage: Int = DEFAULT_DIFF_THRESHOLD,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(baseline: String): YamlAssertVisual {
            return YamlAssertVisual(
                baseline = baseline
            )
        }
    }
}