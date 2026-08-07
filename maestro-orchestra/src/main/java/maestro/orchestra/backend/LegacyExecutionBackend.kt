package maestro.orchestra.backend

import maestro.DeviceInfo
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.util.calculateElementRelativePoint
import maestro.utils.MaestroTimer
import maestro.utils.StringUtils.toRegexSafe
import kotlinx.coroutines.runBlocking
import kotlin.math.max

/**
 * The legacy [ExecutionBackend]: behaviorally identical to today's Orchestra. Device-touching
 * handlers are relocated here verbatim, funnel by funnel, as the router is carved out.
 *
 * The timeout constants are constructor params relocated verbatim from Orchestra.kt:136-137;
 * device-core ignores them (it uses [BackendContext.lookupTimeoutMs] / optionalLookupTimeoutMs).
 */
class LegacyExecutionBackend(
    private val maestro: Maestro,
    private val lookupTimeoutMs: Long = 17000L,
    private val optionalLookupTimeoutMs: Long = 7000L,
) : ExecutionBackend {

    /**
     * Passthrough for now: provisioning still lives in MaestroSessionManager, which constructs the
     * live driver. Phase 2 formalizes the delegation to maestro.driver.open(). No-op is correct here.
     */
    override fun open(appId: String?) {
        // Staged: intentionally a no-op until Phase 2 moves provisioning behind the seam.
    }

    /** Passthrough for now (see [open]); teardown still runs outside the seam. */
    override fun close() {
        // Staged: intentionally a no-op until Phase 2 moves teardown behind the seam.
    }

    override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
        val mutating = when (command) {
            is TapOnElementCommand -> tapOnElement(
                command = command,
                retryIfNoChange = command.retryIfNoChange ?: false,
                waitUntilVisible = command.waitUntilVisible ?: false,
                context = context,
            )

            is LaunchAppCommand -> launchAppCommand(command)
            is StopAppCommand -> stopAppCommand(command)
            is KillAppCommand -> killAppCommand(command)
            is ClearStateCommand -> clearAppStateCommand(command)
            is ClearKeychainCommand -> clearKeychainCommand()
            is OpenLinkCommand -> openLinkCommand(command, context)
            is PressKeyCommand -> pressKeyCommand(command)
            is EraseTextCommand -> eraseTextCommand(command)
            is BackPressCommand -> backPressCommand()
            is HideKeyboardCommand -> hideKeyboardCommand()

            is AssertConditionCommand -> assertConditionCommand(command, context)
            is AssertCommand -> assertConditionCommand(command.toAssertConditionCommand(), context)

            else -> error("LegacyExecutionBackend does not handle ${command::class.simpleName}")
        }
        return CommandExecutionResult(mutating = mutating)
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy =
        runBlocking { maestro.viewHierarchy(excludeKeyboardElements) }

    override val deviceInfo: DeviceInfo
        get() = maestro.cachedDeviceInfo

    // --- Relocated verbatim from Orchestra.tapOnElement (Orchestra.kt:1325-1362) ---
    // config?.appId is threaded in through BackendContext.appId; every maestro.* call is byte-identical.
    private suspend fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
        context: BackendContext,
    ): Boolean {
        val result = findElement(command.selector, optional = command.optional, context = context)


        // Handle element-relative tap if specified
        val relativePoint = command.relativePoint
        if (relativePoint != null) {
            val tapPoint = calculateElementRelativePoint(result.element, relativePoint)

            maestro.tap(
                x = tapPoint.x,
                y = tapPoint.y,
                retryIfNoChange = retryIfNoChange,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
            )
        } else {
            // Default behavior: tap at element center
            maestro.tap(
                element = result.element,
                initialHierarchy = result.hierarchy,
                retryIfNoChange = retryIfNoChange,
                waitUntilVisible = waitUntilVisible,
                longPress = command.longPress ?: false,
                appId = context.appId,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
            )
        }

        return true
    }

    // --- Relocated verbatim from Orchestra (Orchestra.kt ~768-1314) ---
    // Simple findElement-free driver passthroughs. openLinkCommand's config?.appId becomes
    // context.appId (already threaded through BackendContext); every other maestro.* call and
    // return value is byte-identical to Orchestra's.

    private suspend fun launchAppCommand(command: LaunchAppCommand): Boolean {
        if (command.clearKeychain == true) {
            maestro.clearKeychain()
        }
        if (command.clearState == true) {
            maestro.clearAppState(command.appId)
        }

        // For testing convenience, default to allow all on app launch
        val permissions = command.permissions ?: mapOf("all" to "allow")
        maestro.setPermissions(command.appId, permissions)

        maestro.launchApp(
            appId = command.appId,
            launchArguments = command.launchArguments ?: emptyMap(),
            stopIfRunning = command.stopApp ?: true
        )

        return true
    }

    private suspend fun stopAppCommand(command: StopAppCommand): Boolean {
        maestro.stopApp(command.appId)

        return true
    }

    private suspend fun killAppCommand(command: KillAppCommand): Boolean {
        maestro.killApp(command.appId)

        return true
    }

    private suspend fun clearAppStateCommand(command: ClearStateCommand): Boolean {
        maestro.clearAppState(command.appId)
        // Android's clear command also resets permissions
        // Reset all permissions to unset so both platforms behave the same
        maestro.setPermissions(command.appId, mapOf("all" to "unset"))

        return true
    }

    private suspend fun clearKeychainCommand(): Boolean {
        maestro.clearKeychain()

        // No UI effect
        return false
    }

    private suspend fun openLinkCommand(command: OpenLinkCommand, context: BackendContext): Boolean {
        maestro.openLink(command.link, context.appId, command.autoVerify ?: false, command.browser ?: false)

        return true
    }

    private suspend fun pressKeyCommand(command: PressKeyCommand): Boolean {
        maestro.pressKey(command.code)

        return true
    }

    private suspend fun eraseTextCommand(command: EraseTextCommand): Boolean {
        val charactersToErase = command.charactersToErase
        maestro.eraseText(charactersToErase ?: MAX_ERASE_CHARACTERS)
        maestro.waitForAppToSettle()

        return true
    }

    private suspend fun backPressCommand(): Boolean {
        maestro.backPress()
        return true
    }

    private suspend fun hideKeyboardCommand(): Boolean {
        maestro.hideKeyboard()

        // Throw error in case keyboard is still visible
        if (maestro.isKeyboardVisible()) {
            throw MaestroException.HideKeyboardFailure(
                "Couldn't hide the keyboard. This can happen if the app uses a custom input or doesn't expose a standard dismiss action.",
                debugMessage = """
                    Instead of hideKeyboard, try tapping on non-interactive element to hide keyboard. Example:
 
                    - tapOn: 
                        text: 'Static Text on your screen'
                """.trimIndent()
            )
        }

        return true
    }

    // --- Relocated verbatim from Orchestra.assertConditionCommand (Orchestra.kt ~522) ---
    // Same timeout computation, same multi-line debugMessage, same AssertionFailure construction
    // (hierarchyRoot = maestro.viewHierarchy().root). Returns false (non-mutating). evaluateCondition
    // is the backend's copy below; the interaction clock is threaded through context.
    private suspend fun assertConditionCommand(command: AssertConditionCommand, context: BackendContext): Boolean {
        val timeout = (command.timeoutMs() ?: lookupTimeoutMs)
        val debugMessage = """
            Assertion '${command.condition.description()}' failed. Check the UI hierarchy in debug artifacts to verify the element state and properties.

            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state
            - This could be a real regression that needs to be addressed
        """.trimIndent()
        if (!evaluateCondition(command.condition, timeoutMs = timeout, commandOptional = command.optional, context = context)) {
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${command.condition.description()}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = debugMessage
            )
        }

        return false
    }

    // --- Copied from Orchestra.evaluateCondition (Orchestra.kt ~979); NOT deleted there because 3
    // flow-control callers (retry/repeat/runFlow) still need it. Sanctioned shared-helper duplication
    // (same as findElement): it collapses to one copy when flow-control relocates at end of Phase 1.
    // Byte-identical to Orchestra's except the clock is read from context.timeMsOfLastInteraction and
    // findElement is the backend's copy (context = context). No JS engine access: the scriptCondition
    // value is already evaluated by this point (pure string checks).
    private suspend fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long? = null,
        context: BackendContext,
    ): Boolean {
        if (condition == null) {
            return true
        }

        condition.platform?.let {
            if (it != maestro.cachedDeviceInfo.platform) {
                return false
            }
        }

        condition.scriptCondition?.let { value ->
            // Note that script should have been already evaluated by this point

            if (value.isBlank()) {
                return false
            }

            if (value.equals("false", ignoreCase = true)) {
                return false
            }

            if (value == "undefined") {
                return false
            }

            if (value == "null") {
                return false
            }

            if (value.toDoubleOrNull() == 0.0) {
                return false
            }
        }

        condition.visible?.let {
            try {
                findElement(
                    selector = it,
                    timeoutMs = adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs, context.timeMsOfLastInteraction),
                    optional = commandOptional,
                    context = context,
                )
            } catch (_: MaestroException.ElementNotFound) {
                return false
            }
        }

        condition.notVisible?.let {
            val disappeared = MaestroTimer.withTimeoutSuspend(adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs, context.timeMsOfLastInteraction)) {
                try {
                    findElement(
                        selector = it,
                        timeoutMs = 500L,
                        optional = commandOptional,
                        context = context,
                    )
                    // Element is still visible
                    null
                } catch (ignored: MaestroException.ElementNotFound) {
                    // Element was not visible, as we expected
                    true
                }
            }

            if (disappeared != true) {
                return false
            }
        }

        return true
    }

    // --- Copied from Orchestra (still shared there by not-yet-relocated commands) ---
    // findElement + resolveParentHierarchy + buildFilter + childOfDebugMessage are INTENTIONAL,
    // plan-mandated temporary duplicates: Orchestra still owns them because copyTextFromCommand,
    // swipeCommand, evaluateCondition, etc. call them. They collapse to one copy once those callers
    // relocate. The bodies are byte-identical to Orchestra's except the timeout is computed from
    // context.timeMsOfLastInteraction (see adjustedToLatestInteraction below).
    private suspend fun findElement(
        selector: ElementSelector,
        optional: Boolean,
        context: BackendContext,
        timeoutMs: Long? = null,
    ): FindElementResult {
        val timeout =
            timeoutMs ?: adjustedToLatestInteraction(
                if (optional) optionalLookupTimeoutMs
                else lookupTimeoutMs,
                context.timeMsOfLastInteraction,
            )

        val (description, filterFunc) = buildFilter(selector = selector)
        // `selector.childOf` describes the parent to search within, not the child being looked for.
        val parentSelector = selector.childOf
        if (parentSelector != null) {
            var fullHierarchy = ViewHierarchy(TreeNode())
            val found = MaestroTimer.withTimeoutSuspend(timeout) {
                fullHierarchy = maestro.viewHierarchy()
                val parentHierarchy = resolveParentHierarchy(parentSelector, fullHierarchy)
                parentHierarchy?.let { filterFunc(it.aggregate()).firstOrNull()?.toUiElementOrNull() }
            }
            if (found == null) {
                // Both "parent never matched" and "parent matched but child isn't in it" leave `found`
                // null, so re-resolve the parent against the last hierarchy we saw to say which it was.
                // Best effort: a hierarchy that is still changing may resolve differently here than it
                // did on the final loop iteration.
                val parentHierarchy = resolveParentHierarchy(parentSelector, fullHierarchy)
                val (parentDescription, _) = buildFilter(parentSelector)
                // Describe the target without its own childOf clause, so the two roles read
                // distinctly instead of repeating the parent back inside the target.
                val (targetDescription, targetFilter) = buildFilter(selector.copy(childOf = null))
                // A target with no criteria of its own filters to everything, so a count would be
                // meaningless - skip it rather than report the size of the hierarchy.
                val targetMatchesOnScreen =
                    if (targetDescription.isBlank()) null
                    else targetFilter(fullHierarchy.aggregate()).size
                val childOfDebugMessage = childOfDebugMessage(
                    parentMatched = parentHierarchy != null,
                    parentDescription = parentDescription,
                    targetDescription = targetDescription,
                    targetMatchesOnScreen = targetMatchesOnScreen,
                    timeoutMs = timeout,
                )
                if (parentHierarchy == null) {
                    throw MaestroException.ElementNotFound(
                        if (targetDescription.isBlank()) "Parent element not found: $parentDescription"
                        else "Parent element not found: $parentDescription (looking for $targetDescription inside it)",
                        fullHierarchy.root,
                        debugMessage = childOfDebugMessage
                    )
                }
                throw MaestroException.ElementNotFound(
                    "Element not found: $description",
                    fullHierarchy.root,
                    debugMessage = childOfDebugMessage
                )
            }
            return FindElementResult(found, ViewHierarchy(found.treeNode))
        }


        val exceptionDebugMessage = """
            Element with $description not found. Check the UI hierarchy in debug artifacts to verify if the element exists.

            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state.
            - This could be a real regression that needs to be addressed.
        """.trimIndent()
        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            filter = filterFunc
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            maestro.viewHierarchy().root,
            debugMessage = exceptionDebugMessage
        )
    }

    /**
     * Debug text for a failed childOf lookup. Names which half of the selector failed, and whether the
     * target exists on screen outside the parent - that is what separates "my childOf is wrong" from
     * "the element really isn't there". Reaches the console and commands.json, not maestro.log.
     *
     * [targetMatchesOnScreen] is null when the target has no criteria of its own to count.
     */
    private fun childOfDebugMessage(
        parentMatched: Boolean,
        parentDescription: String,
        targetDescription: String,
        targetMatchesOnScreen: Int?,
        timeoutMs: Long,
    ): String {
        val whatFailed = if (parentMatched) {
            "The childOf parent ($parentDescription) matched, but $targetDescription was not found inside it."
        } else {
            "The childOf parent ($parentDescription) matched no element, so its children were never searched."
        }

        val elsewhere = when {
            targetMatchesOnScreen == null -> null
            targetMatchesOnScreen > 0 && parentMatched ->
                "$targetMatchesOnScreen element(s) matched $targetDescription elsewhere on screen, outside that parent."
            targetMatchesOnScreen > 0 ->
                "$targetMatchesOnScreen element(s) matched $targetDescription elsewhere on screen, " +
                    "so the childOf parent is the likely problem."
            else -> "Nothing matched $targetDescription anywhere on screen either."
        }

        // Report the window this lookup actually waited, not the configured timeout: it has already had
        // time since the last interaction deducted (see adjustedToLatestInteraction), so quoting it as
        // "the lookup timeout" would name a number the flow never set.
        val causes = if (parentMatched) {
            """
            - The element may sit outside the parent you selected - check the UI hierarchy in debug artifacts.
            - The element may not have rendered within the ${timeoutMs}ms this lookup waited.
            """.trimIndent()
        } else {
            """
            - The childOf selector may be incorrect - check the UI hierarchy in debug artifacts for elements with slightly different names/properties.
            - The parent may not have rendered within the ${timeoutMs}ms this lookup waited.
            """.trimIndent()
        }

        return listOfNotNull(whatFailed, elsewhere).joinToString(" ") + "\n\nPossible causes:\n" + causes
    }

    private fun resolveParentHierarchy(
        selector: ElementSelector?,
        hierarchy: ViewHierarchy,
    ): ViewHierarchy? {
        if (selector == null) return hierarchy
        val grandparentHierarchy = resolveParentHierarchy(selector.childOf, hierarchy) ?: return null
        val (_, parentFilter) = buildFilter(selector)
        return parentFilter(grandparentHierarchy.aggregate()).firstOrNull()
            ?.let { ViewHierarchy(it) }
    }

    private fun buildFilter(
        selector: ElementSelector,
    ): FilterWithDescription {
        val basicFilters = mutableListOf<ElementFilter>()
        val relativeFilters = mutableListOf<ElementFilter>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                basicFilters += Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS))
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                basicFilters += Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS))
            }
        selector.size
            ?.let {
                descriptions += "Size: $it"
                basicFilters += Filters.sizeMatches(
                    width = it.width,
                    height = it.height,
                    tolerance = it.tolerance,
                ).asFilter()
            }

        selector.below
            ?.let {
                descriptions += "Below: ${it.description()}"
                relativeFilters += Filters.below(buildFilter(it).filterFunc)
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                relativeFilters += Filters.above(buildFilter(it).filterFunc)
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                relativeFilters += Filters.leftOf(buildFilter(it).filterFunc)
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                relativeFilters += Filters.rightOf(buildFilter(it).filterFunc)
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                relativeFilters += Filters.containsChild(buildFilter(it).filterFunc)
            }

        selector.containsDescendants
            ?.let { descendantSelectors ->
                val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
                descriptions += "Contains descendants: $descendantDescriptions"
                relativeFilters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it).filterFunc })
            }

        selector.childOf
            ?.let {
                descriptions += "Child of: ${it.description()}"
            }

        selector.traits
            ?.map {
                TraitFilters.buildFilter(it)
            }
            ?.forEach { (description, filter) ->
                descriptions += description
                basicFilters += filter
            }

        selector.index
            ?.let {
                descriptions += "Index: ${it.toDoubleOrNull()?.toInt() ?: it}"
            }

        selector.enabled
            ?.let {
                descriptions += if (it) {
                    "Enabled"
                } else {
                    "Disabled"
                }
                basicFilters += Filters.enabled(it)
            }

        selector.selected
            ?.let {
                descriptions += if (it) {
                    "Selected"
                } else {
                    "Not selected"
                }
                basicFilters += Filters.selected(it)
            }

        selector.checked
            ?.let {
                descriptions += if (it) {
                    "Checked"
                } else {
                    "Not checked"
                }
                basicFilters += Filters.checked(it)
            }

        selector.focused
            ?.let {
                descriptions += if (it) {
                    "Focused"
                } else {
                    "Not focused"
                }
                basicFilters += Filters.focused(it)
            }

        selector.css
            ?.let {
                descriptions += "CSS: $it"
                basicFilters += Filters.css(maestro, it)
            }

        // Apply deepestMatchingElement only to basic filters, then intersect with relative filters
        val basicFilter = if (basicFilters.isNotEmpty()) {
            Filters.deepestMatchingElement(Filters.intersect(basicFilters))
        } else {
            { nodes -> nodes } // Identity filter if no basic filters
        }

        val allFilters = listOf(basicFilter) + relativeFilters
        var resultFilter = Filters.intersect(allFilters)

        resultFilter = selector.index
            ?.toDouble()
            ?.toInt()
            ?.let {
                Filters.compose(
                    resultFilter,
                    Filters.index(it)
                )
            } ?: Filters.compose(
            resultFilter,
            Filters.clickableFirst()
        )

        return FilterWithDescription(
            descriptions.joinToString(", "),
            resultFilter,
        )
    }

    // Behavior-neutral copy of Orchestra.adjustedToLatestInteraction that reads the clock from
    // context instead of the Orchestra field. Orchestra keeps owning the clock this phase; this
    // duplicate is removed when the clock relocates wholesale at the end of Phase 1.
    private fun adjustedToLatestInteraction(timeMs: Long, timeMsOfLastInteraction: Long) = max(
        0,
        timeMs - (System.currentTimeMillis() - timeMsOfLastInteraction),
    )

    companion object {
        // Copied verbatim from Orchestra.REGEX_OPTIONS so buildFilter stays byte-identical.
        private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        // Copied verbatim from Orchestra.MAX_ERASE_CHARACTERS so eraseTextCommand stays byte-identical.
        private const val MAX_ERASE_CHARACTERS = 50
    }
}
