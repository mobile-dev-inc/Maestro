package conductor.orchestra.yaml

import conductor.orchestra.AssertCommand
import conductor.orchestra.BackPressCommand
import conductor.orchestra.ConductorCommand
import conductor.orchestra.ElementSelector
import conductor.orchestra.InputTextCommand
import conductor.orchestra.ScrollCommand
import conductor.orchestra.TapOnElementCommand

data class YamlFluentCommand(
    val tapOn: YamlElementSelector? = null,
    val assertVisible: YamlElementSelector? = null,
    val action: String? = null,
    val inputText: String? = null,
) {

    @SuppressWarnings("ComplexMethod")
    fun toCommand(): ConductorCommand {
        return when {
            tapOn != null -> ConductorCommand(
                tapOnElement = TapOnElementCommand(
                    toElementSelector(tapOn),
                    tapOn.retryTapIfNoChange ?: true
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

    private fun toElementSelector(tapOn: YamlElementSelector): ElementSelector {
        val size = if (tapOn.width != null || tapOn.height != null) {
            ElementSelector.SizeSelector(
                width = tapOn.width,
                height = tapOn.height,
                tolerance = tapOn.tolerance,
            )
        } else {
            null
        }

        return ElementSelector(
            textRegex = tapOn.text,
            idRegex = tapOn.id,
            size = size,
            optional = tapOn.optional ?: false,
        )
    }
}
