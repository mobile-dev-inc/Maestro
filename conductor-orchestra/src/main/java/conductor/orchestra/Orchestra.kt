package conductor.orchestra

import conductor.Conductor
import conductor.ConductorException
import conductor.ElementLookupPredicate
import conductor.Predicates
import conductor.UiElement

class Orchestra(
    private val conductor: Conductor,
    private val lookupTimeoutMs: Long = 10000L,
    private val optionalLookupTimeoutMs: Long = 3000L,
    private val onCommandStart: (Int, ConductorCommand) -> Unit = { _, _ -> },
    private val onCommandComplete: (Int, ConductorCommand) -> Unit = { _, _ -> },
    private val onCommandFailed: (Int, ConductorCommand, Throwable) -> Unit = { _, _, e -> throw e },
) {

    fun executeCommands(commands: List<ConductorCommand>) {
        commands.forEachIndexed { index, command ->
            onCommandStart(index, command)
            try {
                executeCommand(command)
                onCommandComplete(index, command)
            } catch (e: Throwable) {
                onCommandFailed(index, command, e)
                return
            }
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
            command.launchAppCommand != null -> command.launchAppCommand?.let { launchAppCommand(it) }
        }
    }

    private fun launchAppCommand(it: LaunchAppCommand) {
        try {
            conductor.launchApp(it.appId)
        } catch (e: Exception) {
            throw ConductorException.UnableToLaunchApp("Unable to launch app ${it.appId}: ${e.message}")
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
        } catch (e: ConductorException.ElementNotFound) {

            if (!command.selector.optional) {
                throw e
            }
        }
    }

    private fun findElement(selector: ElementSelector): UiElement {
        val timeout = if (selector.optional) {
            optionalLookupTimeoutMs
        } else {
            lookupTimeoutMs
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
        ) ?: throw ConductorException.ElementNotFound(
            "Element not found: ${descriptions.joinToString(", ")}",
        )
    }
}
