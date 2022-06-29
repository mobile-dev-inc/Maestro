package conductor.orchestra

import conductor.Conductor
import conductor.ElementLookupPredicate
import conductor.Predicates
import conductor.UiElement

class Orchestra(private val conductor: Conductor) {

    fun executeCommands(commands: List<ConductorCommand>) {
        commands.forEach {
            executeCommand(it)
        }
    }

    private fun executeCommand(command: ConductorCommand) {
        when {
            command.tapOnElement != null -> command.tapOnElement?.let {
                tapOnElement(it, it.retryIfNoChange ?: true)
            }
            command.backPressCommand != null -> conductor.backPress()
            command.scrollCommand != null -> conductor.scrollVertical()
            command.assertCommand != null -> command.assertCommand?.let { assertCommand(it) }
            command.inputTextCommand != null -> command.inputTextCommand?.let { inputTextCommand(it) }
        }
    }

    private fun inputTextCommand(command: InputTextCommand) {
        conductor.inputText(command.text)
    }

    private fun assertCommand(command: AssertCommand) {
        command.visible?.let { assertVisible(it) }
    }

    private fun assertVisible(selector: ElementSelector) {
        findElement(selector) // Throws if element is not found
    }

    private fun tapOnElement(command: TapOnElementCommand, retryIfNoChange: Boolean) {
        try {
            val element = findElement(command.selector)
            conductor.tap(element, retryIfNoChange)
        } catch (e: Conductor.NotFoundException) {

            if (!command.selector.optional) {
                throw e
            }
        }
    }

    private fun findElement(selector: ElementSelector): UiElement {
        val timeout = if (selector.optional) {
            3000L
        } else {
            10000L
        }

        val predicates = mutableListOf<ElementLookupPredicate>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                predicates += Predicates.textMatches(it.toRegex(RegexOption.IGNORE_CASE))
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                predicates += Predicates.idMatches(it.toRegex(RegexOption.IGNORE_CASE))
            }

        selector.size
            ?.let {
                descriptions += "Size: $it"
                predicates += Predicates.sizeMatches(
                    width = it.width,
                    height = it.height,
                    tolerance = it.tolerance,
                )
            }

        return conductor.findElementWithTimeout(
            timeoutMs = timeout,
            predicate = Predicates.allOf(predicates),
        ) ?: throw Conductor.NotFoundException(
            "Element not found: ${descriptions.joinToString(", ")}",
        )
    }
}
