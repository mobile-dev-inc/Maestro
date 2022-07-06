package conductor.orchestra.yaml

import conductor.orchestra.AssertCommand
import conductor.orchestra.BackPressCommand
import conductor.orchestra.ConductorCommand
import conductor.orchestra.ElementSelector
import conductor.orchestra.InputTextCommand
import conductor.orchestra.LaunchAppCommand
import conductor.orchestra.ScrollCommand
import conductor.orchestra.TapOnElementCommand

data class YamlFluentCommand(
    val tapOn: YamlElementSelectorUnion? = null,
    val assertVisible: YamlElementSelector? = null,
    val action: String? = null,
    val inputText: String? = null,
    val launchApp: String? = null,
) {

    @SuppressWarnings("ComplexMethod")
    fun toCommand(): ConductorCommand {
        return when {
            launchApp != null -> ConductorCommand(
                launchAppCommand = LaunchAppCommand(launchApp)
            )
            tapOn != null -> ConductorCommand(
                tapOnElement = TapOnElementCommand(
                    toElementSelector(tapOn),
                    (tapOn as? YamlElementSelector)?.retryTapIfNoChange ?: true
                )
            )
            assertVisible != null -> ConductorCommand(
                assertCommand = AssertCommand(
                    visible = toElementSelector(assertVisible),
                )
            )
            inputText != null -> ConductorCommand(
                inputTextCommand = InputTextCommand(inputText)
            )
            action != null -> when (action) {
                "back" -> ConductorCommand(backPressCommand = BackPressCommand())
                "scroll" -> ConductorCommand(scrollCommand = ScrollCommand())
                else -> throw IllegalStateException("Unknown navigation target: $action")
            }
            else -> throw IllegalStateException("No mapping provided for $this")
        }
    }

    private fun toElementSelector(selectorUnion: YamlElementSelectorUnion): ElementSelector {
        return if (selectorUnion is StringElementSelector) {
            ElementSelector(
                textRegex = selectorUnion.value,
            )
        } else if (selectorUnion is YamlElementSelector) {
            toElementSelector(selectorUnion)
        } else {
            throw IllegalStateException("Unknown selector type: $selectorUnion")
        }
    }

    private fun toElementSelector(selector: YamlElementSelector): ElementSelector {
        val size = if (selector.width != null || selector.height != null) {
            ElementSelector.SizeSelector(
                width = selector.width,
                height = selector.height,
                tolerance = selector.tolerance,
            )
        } else {
            null
        }

        return ElementSelector(
            textRegex = selector.text,
            idRegex = selector.id,
            size = size,
            optional = selector.optional ?: false,
        )
    }
}
