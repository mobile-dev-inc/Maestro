package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonFormat
import maestro.ScrollDirection
import maestro.orchestra.ScrollUntilVisibleCommand

data class YamlScrollUntilVisible(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
    val direction: ScrollDirection = ScrollDirection.DOWN,
    val element: YamlElementSelectorUnion,
    val timeout: Long = ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS,
    val speed: Int = ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION,
    val visibilityPercentage: Int = ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE,
    val centerElement: Boolean = ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT
) {
    fun speedToDuration(): Long {
        return (1000 * (100 - speed).toDouble() / 100).toLong() + 1
    }
}