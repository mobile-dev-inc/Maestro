package conductor.orchestra

import conductor.Conductor
import conductor.Crawler
import conductor.UiElement

class Orchestra(
        private val conductor: Conductor,
) {
    private val crawler = object : Crawler {
        override fun crawl(conductor: Conductor, maxActions: Int) {
            TODO("Not yet implemented")
        }
    }

    fun executeCommands(commands: List<ConductorCommand>) {
        commands.forEach {
            executeCommand(it)
            Thread.sleep(3000)
        }
    }

    private fun executeCommand(command: ConductorCommand) {
        when {
            command.tapOnElement != null -> command.tapOnElement?.let { tapOnElement(it) }
            command.backPressCommand != null -> conductor.backPress()
            command.scrollCommand != null -> conductor.scrollVertical()
            command.crawlerCommand != null -> command.crawlerCommand?.let { crawler.crawl(conductor, it.maxActions) }
        }
    }

    private fun tapOnElement(command: TapOnElementCommand) {
        val element = findElement(command.selector)
                ?: throw AssertionError("Element not found: ${command.selector}")

        conductor.tap(element)
    }

    private fun findElement(selector: ElementSelector): UiElement? {
        when {
            selector.textRegex != null -> {
                return selector.textRegex
                        ?.let {
                            conductor.findElementByRegexp(it.toRegex(RegexOption.IGNORE_CASE), 10000)
                        }
            }
            selector.idRegex != null -> {
                return selector.idRegex
                        ?.let { conductor.findElementByIdRegex(it.toRegex(RegexOption.IGNORE_CASE), 10000) }
            }
        }
        return null
    }
}
