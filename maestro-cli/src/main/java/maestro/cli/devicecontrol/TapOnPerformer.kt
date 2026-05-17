package maestro.cli.devicecontrol

import kotlinx.coroutines.runBlocking
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand

object TapOnPerformer {

    data class Request(
        val text: String? = null,
        val id: String? = null,
        val index: Int? = null,
        val useFuzzyMatching: Boolean = true,
        val enabled: Boolean? = null,
        val checked: Boolean? = null,
        val focused: Boolean? = null,
        val selected: Boolean? = null,
    ) {
        init {
            require(text != null || id != null) { "Either text or id must be provided" }
        }
    }

    fun escapeRegex(input: String): String {
        return input.replace(Regex("[()\\[\\]{}+*?^$|.\\\\]")) { "\\${it.value}" }
    }

    fun toSelector(request: Request): ElementSelector {
        return ElementSelector(
            textRegex = if (request.useFuzzyMatching && request.text != null) ".*${escapeRegex(request.text)}.*" else request.text,
            idRegex = if (request.useFuzzyMatching && request.id != null) ".*${escapeRegex(request.id)}.*" else request.id,
            index = request.index?.toString(),
            enabled = request.enabled,
            checked = request.checked,
            focused = request.focused,
            selected = request.selected,
        )
    }

    fun perform(orchestra: Orchestra, request: Request) {
        val command = TapOnElementCommand(
            selector = toSelector(request),
            retryIfNoChange = true,
            waitUntilVisible = true,
        )

        runBlocking {
            orchestra.runFlow(listOf(MaestroCommand(command = command)))
        }
    }
}
