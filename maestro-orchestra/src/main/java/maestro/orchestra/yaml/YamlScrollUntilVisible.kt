package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonFormat
import maestro.ScrollDirection
import maestro.orchestra.ScrollUntilVisibleCommand

data class YamlScrollUntilVisible(
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES])
    val direction: ScrollDirection = ScrollDirection.DOWN,
    val element: YamlElementSelectorUnion,
    val timeout: Long = ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS,
)