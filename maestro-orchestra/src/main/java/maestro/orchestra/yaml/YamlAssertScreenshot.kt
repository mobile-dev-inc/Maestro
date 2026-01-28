package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.orchestra.ElementSelector

private const val DEFAULT_DIFF_THRESHOLD = 95.0

data class YamlAssertScreenshot(
    val path: String,
    val thresholdPercentage: Double = DEFAULT_DIFF_THRESHOLD,
    val cropOn: YamlElementSelectorUnion? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(path: String): YamlAssertScreenshot {
            return YamlAssertScreenshot(
                path = path
            )
        }
    }
}