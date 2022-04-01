package conductor.orchestra.yaml

import conductor.orchestra.BackPressCommand
import conductor.orchestra.ConductorCommand
import conductor.orchestra.ElementSelector
import conductor.orchestra.CrawlerCommand
import conductor.orchestra.ScrollCommand
import conductor.orchestra.TapOnElementCommand

data class YamlFluentCommand(
    val tapOnText: String? = null,
    val tapOnId: String? = null,
    val action: String? = null,
    val maxActions: Int? = null,
) {

    fun toCommand(): ConductorCommand {
        return when {
            tapOnText != null -> ConductorCommand(
                tapOnElement = TapOnElementCommand(ElementSelector(textRegex = tapOnText))
            )
            tapOnId != null -> ConductorCommand(
                tapOnElement = TapOnElementCommand(ElementSelector(idRegex = tapOnId))
            )
            maxActions != null -> ConductorCommand(
                crawlerCommand = CrawlerCommand(maxActions)
            )
            action != null -> when (action) {
                "back" -> ConductorCommand(backPressCommand = BackPressCommand())
                "scroll" -> ConductorCommand(scrollCommand = ScrollCommand())
                else -> throw IllegalStateException("Unknown navigation target: $action")
            }
            else -> throw IllegalStateException("No mapping provided for $this")
        }
    }
}
