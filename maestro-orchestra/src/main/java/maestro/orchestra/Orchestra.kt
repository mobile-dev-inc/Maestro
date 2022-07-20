/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.orchestra

import maestro.Maestro
import maestro.MaestroException
import maestro.ElementLookupPredicate
import maestro.Predicates
import maestro.UiElement

class Orchestra(
    private val maestro: Maestro,
    private val lookupTimeoutMs: Long = 15000L,
    private val optionalLookupTimeoutMs: Long = 3000L,
    private val onCommandStart: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandComplete: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandFailed: (Int, MaestroCommand, Throwable) -> Unit = { _, _, e -> throw e },
) {

    fun executeCommands(commands: List<MaestroCommand>) {
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

    private fun executeCommand(command: MaestroCommand) {
        when {
            command.tapOnElement != null -> command.tapOnElement?.let {
                tapOnElement(
                    it,
                    it.retryIfNoChange ?: true,
                    it.waitUntilVisible ?: true,
                )
            }
            command.tapOnPoint != null -> command.tapOnPoint?.let {
                tapOnPoint(
                    it,
                    it.retryIfNoChange ?: true,
                )
            }
            command.backPressCommand != null -> maestro.backPress()
            command.scrollCommand != null -> maestro.scrollVertical()
            command.swipeCommand != null -> command.swipeCommand?.let { swipeCommand(it) }
            command.assertCommand != null -> command.assertCommand?.let { assertCommand(it) }
            command.inputTextCommand != null -> command.inputTextCommand?.let { inputTextCommand(it) }
            command.launchAppCommand != null -> command.launchAppCommand?.let { launchAppCommand(it) }
        }
    }

    private fun launchAppCommand(it: LaunchAppCommand) {
        try {
            maestro.launchApp(it.appId)
        } catch (e: Exception) {
            throw MaestroException.UnableToLaunchApp("Unable to launch app ${it.appId}: ${e.message}")
        }
    }

    private fun inputTextCommand(command: InputTextCommand) {
        maestro.inputText(command.text)
    }

    private fun assertCommand(command: AssertCommand) {
        command.visible?.let { assertVisible(it) }
    }

    private fun assertVisible(selector: ElementSelector) {
        findElement(selector) // Throws if element is not found
    }

    private fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
    ) {
        try {
            val element = findElement(command.selector)
            maestro.tap(
                element,
                retryIfNoChange,
                waitUntilVisible
            )
        } catch (e: MaestroException.ElementNotFound) {

            if (!command.selector.optional) {
                throw e
            }
        }
    }

    private fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ) {
        maestro.tap(
            command.x,
            command.y,
            retryIfNoChange,
        )
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
                predicates += Predicates.textMatches(it.toRegex(REGEX_OPTIONS))
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                predicates += Predicates.idMatches(it.toRegex(REGEX_OPTIONS))
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

        selector.below
            ?.let {
                descriptions += "Below: ${it.description()}"
                predicates += Predicates.below(findElement(it))
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                predicates += Predicates.above(findElement(it))
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                predicates += Predicates.leftOf(findElement(it))
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                predicates += Predicates.rightOf(findElement(it))
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                predicates += Predicates.containsChild(findElement(it))
            }

        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            predicate = Predicates.allOf(predicates),
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: ${descriptions.joinToString(", ")}",
            maestro.viewHierarchy(),
        )
    }

    private fun swipeCommand(command: SwipeCommand) {
        maestro.swipe(command.startPoint, command.endPoint)
    }

    companion object {

        val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

    }
}
