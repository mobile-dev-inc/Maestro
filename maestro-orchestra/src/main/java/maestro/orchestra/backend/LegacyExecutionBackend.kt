package maestro.orchestra.backend

import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.AssertCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.AssertLightModeCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.DarkModeValue
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetDarkModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.ToggleDarkModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.geo.Traveller
import maestro.orchestra.util.calculateElementRelativePoint
import maestro.toSwipeDirection
import okio.Sink
import maestro.utils.MaestroTimer
import maestro.utils.StringUtils.toRegexSafe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import java.io.File
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

    override val backendId: String = "legacy"

    /**
     * Provisioning/connection still lives in MaestroSessionManager (the live driver is already
     * connected by the time this runs), so open() does NOT connect — Phase 2 formalizes the
     * delegation to maestro.driver.open(). It DOES apply the one per-run device toggle that used to
     * live in Orchestra.initAndroidChromeDevTools: replicated verbatim so legacy stays byte-identical.
     */
    override fun open(appId: String?, config: MaestroConfig?) {
        if (config == null) return
        val enable = config.ext["androidWebViewHierarchy"] == "devtools"
        runBlocking { maestro.setAndroidChromeDevToolsEnabled(enable) }
    }

    /** Passthrough for now (see [open]); teardown still runs outside the seam. */
    override fun close() {
        // Staged: intentionally a no-op until Phase 2 moves teardown behind the seam.
    }

    // The element the current command resolved, captured for the differential trace as a side effect
    // of the resolution the command already does. Reset per execute() call; set by the element-
    // resolving handlers below (tap/swipe-from-element/scrollUntilVisible/copyTextFrom/assert-visible).
    // NO extra device read: it only reads the FindElementResult the handler already computed. Null for
    // commands that resolve no element.
    private var lastChosenElement: ChosenElement? = null

    override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
        lastChosenElement = null
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

            is TapOnPointCommand -> tapOnPoint(command, command.retryIfNoChange ?: false)
            is TapOnPointV2Command -> tapOnPointV2Command(command)
            is ScrollCommand -> scrollVerticalCommand()
            is SetPermissionsCommand -> setPermissionsCommand(command)
            is WaitForAnimationToEndCommand -> waitForAnimationToEndCommand(command)
            is SetLocationCommand -> setLocationCommand(command)
            is SetOrientationCommand -> setOrientationCommand(command)
            is AddMediaCommand -> addMediaCommand(command.mediaPaths)
            is SetAirplaneModeCommand -> setAirplaneMode(command)
            is ToggleAirplaneModeCommand -> toggleAirplaneMode()
            is SetDarkModeCommand -> setDarkMode(command)
            is ToggleDarkModeCommand -> toggleDarkMode()
            is AssertDarkModeCommand -> assertDarkMode(expected = true)
            is AssertLightModeCommand -> assertDarkMode(expected = false)
            is TravelCommand -> travelCommand(command)

            is ScrollUntilVisibleCommand -> scrollUntilVisible(command, context)
            is SwipeCommand -> swipeCommand(command, context)

            is InputTextCommand -> inputTextCommand(command)
            is InputRandomCommand -> inputTextRandomCommand(command)
            is PasteTextCommand -> pasteText(context)

            is CopyTextFromCommand -> {
                val text = copyTextFromCommand(command, context)
                return CommandExecutionResult(mutating = false, output = text, trace = passTrace())
            }

            else -> error("LegacyExecutionBackend does not handle ${command::class.simpleName}")
        }
        return CommandExecutionResult(mutating = mutating, trace = passTrace())
    }

    // Reaching a return means the command succeeded (a failed assertion/lookup throws before here) —
    // the router derives the real verdict from the lifecycle outcome (a thrown command never returns a
    // trace at all). chosenElement is whatever the handler resolved, or null.
    private fun passTrace() = StepTrace(chosenElement = lastChosenElement)

    // Build a ChosenElement from an already-resolved element — no device read. [centerX]/[centerY] is
    // the coordinate the command's gesture actually used (element center, or an element-relative
    // point), which is what the differential gate compares.
    private fun chosenElementOf(element: maestro.UiElement, centerX: Int, centerY: Int): ChosenElement {
        val b = element.bounds
        val attrs = element.treeNode.attributes
        return ChosenElement(
            x = b.x, y = b.y, width = b.width, height = b.height,
            centerX = centerX, centerY = centerY,
            text = attrs["text"]?.ifEmpty { null },
            resourceId = attrs["resource-id"]?.ifEmpty { null },
            index = null,
        )
    }

    override fun hierarchySnapshot(): TreeNode? =
        runBlocking { maestro.viewHierarchy().root }

    // --- Device primitives (Task 1.9). Each delegates VERBATIM to the same maestro.* call Orchestra
    // used to make directly, so the legacy backend stays byte-identical. ---

    override suspend fun takeScreenshot(
        out: Sink,
        compressed: Boolean,
        cropOn: ElementSelector?,
        optional: Boolean,
        context: BackendContext?,
    ) {
        if (cropOn == null) {
            // No crop: byte-identical to Orchestra's old direct maestro.takeScreenshot(out, compressed).
            maestro.takeScreenshot(out, compressed, null)
            return
        }
        // Crop resolution folded below the seam: resolve the element exactly as the router used to,
        // apply the SAME positive-dimensions guard, then capture cropped exactly as before. On invalid
        // dimensions throw InvalidCropDimensions carrying the bounds; the router re-wraps it.
        val ctx = requireNotNull(context) { "takeScreenshot with cropOn requires a BackendContext" }
        val bounds = findElement(cropOn, optional = optional, context = ctx).element.bounds
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw InvalidCropDimensions(bounds)
        }
        maestro.takeScreenshot(out, compressed, bounds)
    }

    override suspend fun startScreenRecording(out: Sink): ScreenRecording =
        maestro.startScreenRecording(out)

    override suspend fun startDeviceLogCapture() = maestro.startDeviceLogCapture()
    override suspend fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> =
        maestro.stopAndCollectDeviceLogs(outputDir)
    override suspend fun collectCrashArtifacts(appId: String?, sinceEpochMs: Long, outputDir: File): List<CapturedDeviceArtifact> =
        maestro.collectCrashArtifacts(appId, sinceEpochMs, outputDir)

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

            lastChosenElement = chosenElementOf(result.element, tapPoint.x, tapPoint.y)
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
            val center = result.element.bounds.center()
            lastChosenElement = chosenElementOf(result.element, center.x, center.y)
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

    // --- Relocated verbatim from Orchestra (Orchestra.kt various offsets) ---
    // Second simple findElement-free driver passthrough batch (Task 1.5). Every maestro.* call and
    // return value is byte-identical to Orchestra's.

    private suspend fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ): Boolean {
        maestro.tap(
            x = command.x,
            y = command.y,
            retryIfNoChange = retryIfNoChange,
            longPress = command.longPress ?: false,
            tapRepeat = command.repeat,
        )

        return true
    }

    private suspend fun tapOnPointV2Command(
        command: TapOnPointV2Command,
    ): Boolean {
        val point = command.point

        if (point.contains("%")) {
            val (percentX, percentY) = point
                .replace("%", "")
                .split(",")
                .map { it.trim().toInt() }

            if (percentX !in 0..100 || percentY !in 0..100) {
                throw MaestroException.InvalidCommand("Invalid point: $point")
            }

            maestro.tapOnRelative(
                percentX = percentX,
                percentY = percentY,
                retryIfNoChange = command.retryIfNoChange ?: false,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        } else {
            val (x, y) = point.split(",")
                .map {
                    it.trim().toInt()
                }

            maestro.tap(
                x = x,
                y = y,
                retryIfNoChange = command.retryIfNoChange ?: false,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        }

        return true
    }

    private suspend fun scrollVerticalCommand(): Boolean {
        maestro.scrollVertical()
        return true
    }

    private suspend fun setPermissionsCommand(command: SetPermissionsCommand): Boolean {
        maestro.setPermissions(command.appId, command.permissions)

        // Setting permissions occurs behind the scenes and won't alter screen state.
        // Android and iOS provide no mechanism for subscribing to permissions events.
        return false
    }

    private suspend fun waitForAnimationToEndCommand(command: WaitForAnimationToEndCommand): Boolean {
        maestro.waitForAnimationToEnd(command.timeout)

        return true
    }

    private suspend fun setLocationCommand(command: SetLocationCommand): Boolean {
        maestro.setLocation(command.latitude, command.longitude)

        return true
    }

    private suspend fun setOrientationCommand(command: SetOrientationCommand): Boolean {
        maestro.setOrientation(command.resolvedOrientation())

        return true
    }

    private suspend fun addMediaCommand(mediaPaths: List<String>): Boolean {
        maestro.addMedia(mediaPaths)
        return true
    }

    private suspend fun setAirplaneMode(command: SetAirplaneModeCommand): Boolean {
        when (command.value) {
            AirplaneValue.Enable -> maestro.setAirplaneModeState(true)
            AirplaneValue.Disable -> maestro.setAirplaneModeState(false)
        }

        return true
    }

    private suspend fun toggleAirplaneMode(): Boolean {
        maestro.setAirplaneModeState(!maestro.isAirplaneModeEnabled())
        return true
    }

    private suspend fun setDarkMode(command: SetDarkModeCommand): Boolean {
        when (command.value) {
            DarkModeValue.Enable -> maestro.setDarkModeState(true)
            DarkModeValue.Disable -> maestro.setDarkModeState(false)
        }

        return true
    }

    private suspend fun toggleDarkMode(): Boolean {
        maestro.setDarkModeState(!maestro.isDarkModeEnabled())
        return true
    }

    // Byte-identical copy of Orchestra.assertDarkMode(expected: Boolean) (Task 1.5). Both
    // AssertDarkModeCommand (expected = true) and AssertLightModeCommand (expected = false, Task
    // 1.6) route here now — a single shared copy, not a second duplicate. Orchestra's own
    // assertDarkMode is now dead code (nothing there calls it anymore); left in place per the
    // brief, to be removed in the later consolidation pass.
    private suspend fun assertDarkMode(expected: Boolean): Boolean {
        val actual = maestro.isDarkModeEnabled()
        if (actual != expected) {
            val expectedState = if (expected) "enabled" else "disabled"
            val actualState = if (actual) "dark mode" else "light mode"
            throw MaestroException.AssertionFailure(
                message = "Assertion failed: expected dark mode to be $expectedState, but it was ${if (actual) "enabled" else "disabled"}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "The device's system-wide appearance is currently $actualState. Use setDarkMode or toggleDarkMode to change it before this assertion."
            )
        }

        return false
    }

    private suspend fun travelCommand(command: TravelCommand): Boolean {
        Traveller.travel(
            maestro = maestro,
            points = command.points,
            speedMPS = command.speedMPS ?: 4.0,
        )

        return true
    }

    // --- Relocated verbatim from Orchestra.swipeCommand (Orchestra.kt ~1440-1486) ---
    // The element-relative branch calls findElement, threading context (the backend's own copy,
    // same as tapOnElement above). Every maestro.swipe* call is byte-identical to Orchestra's.
    private suspend fun swipeCommand(command: SwipeCommand, context: BackendContext): Boolean {
        val elementSelector = command.elementSelector
        val direction = command.direction
        val startRelative = command.startRelative
        val endRelative = command.endRelative
        val start = command.startPoint
        val end = command.endPoint
        when {
            elementSelector != null && direction != null -> {
                val uiElement = findElement(elementSelector, optional = command.optional, context = context)
                val startPoint = command.relativePoint
                    ?.let { calculateElementRelativePoint(uiElement.element, it) }
                    ?: uiElement.element.bounds.center()
                lastChosenElement = chosenElementOf(uiElement.element, startPoint.x, startPoint.y)
                maestro.swipe(
                    direction,
                    startPoint,
                    command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                )
            }

            startRelative != null && endRelative != null -> {
                maestro.swipe(
                    startRelative = startRelative,
                    endRelative = endRelative,
                    duration = command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                )
            }

            direction != null -> maestro.swipe(
                swipeDirection = direction,
                duration = command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )

            start != null && end != null -> maestro.swipe(
                startPoint = start,
                endPoint = end,
                duration = command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )

            else -> error("Illegal arguments for swiping")
        }
        return true
    }

    // --- Relocated verbatim from Orchestra (input & clipboard cluster, Task 1.7) ---
    // inputText/inputTextRandom are clean passthroughs. copyTextFrom RESOLVES + EXTRACTS and returns
    // the text to the router (which owns copiedText/jsEngine above the seam). pasteText READS the
    // clipboard value the router supplied via context.copiedText. resolveText moved here verbatim —
    // it was private to Orchestra and used only by copyTextFrom.

    private suspend fun inputTextCommand(command: InputTextCommand): Boolean {
        maestro.inputText(command.text)

        return true
    }

    private suspend fun inputTextRandomCommand(command: InputRandomCommand): Boolean {
        inputTextCommand(InputTextCommand(text = command.genRandomString()))

        return true
    }

    private suspend fun copyTextFromCommand(command: CopyTextFromCommand, context: BackendContext): String {
        val result = findElement(command.selector, optional = command.optional, context = context)
        val center = result.element.bounds.center()
        lastChosenElement = chosenElementOf(result.element, center.x, center.y)
        return resolveText(result.element.treeNode.attributes)
            ?: throw MaestroException.UnableToCopyTextFromElement("Element does not contain text to copy: ${result.element}")
    }

    private fun resolveText(attributes: MutableMap<String, String>): String? {
        return if (!attributes["text"].isNullOrEmpty()) {
            attributes["text"]
        } else if (!attributes["hintText"].isNullOrEmpty()) {
            attributes["hintText"]
        } else {
            attributes["accessibilityText"]
        }
    }

    private suspend fun pasteText(context: BackendContext): Boolean {
        context.copiedText?.let { maestro.inputText(it) }
        return true
    }

    // --- Relocated verbatim from Orchestra.scrollUntilVisible (Orchestra.kt ~698-771) ---
    // The hot-poll findElement call (timeoutMs = 500L) uses the backend's own findElement,
    // threading context. Loop structure, timeouts, and every maestro.* call are byte-identical.
    private suspend fun scrollUntilVisible(command: ScrollUntilVisibleCommand, context: BackendContext): Boolean {
        val endTime = System.currentTimeMillis() + command.timeout.toLong()
        val direction = command.direction.toSwipeDirection()
        val deviceInfo = maestro.deviceInfo()

        var retryCenterCount = 0
        val maxRetryCenterCount = 4 // for when the list is no longer scrollable (last element) but the element is visible

        do {
            yield()
            try {
                val element = findElement(command.selector, optional = command.optional, context = context, timeoutMs = 500L).element
                val visibility = element.getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)

                logger.info("Scrolling try count: $retryCenterCount, DeviceWidth: ${deviceInfo.widthGrid}, DeviceWidth: ${deviceInfo.heightGrid}")
                logger.info("Element bounds: ${element.bounds}")
                logger.info("Visibility Percent: $visibility")
                logger.info("Command centerElement: $command.centerElement")
                logger.info("visibilityPercentageNormalized: ${command.visibilityPercentageNormalized}")

                if (command.centerElement && visibility > 0.1 && retryCenterCount <= maxRetryCenterCount) {
                    if (element.isElementNearScreenCenter(direction, deviceInfo.widthGrid, deviceInfo.heightGrid)) {
                        val center = element.bounds.center()
                        lastChosenElement = chosenElementOf(element, center.x, center.y)
                        return true
                    }
                    retryCenterCount++
                } else if (visibility >= command.visibilityPercentageNormalized) {
                    val center = element.bounds.center()
                    lastChosenElement = chosenElementOf(element, center.x, center.y)
                    return true
                }
            } catch (ignored: MaestroException.ElementNotFound) {
                logger.warn("Error: $ignored")
            }
            maestro.swipeFromCenter(
                direction,
                durationMs = command.scrollDuration.toLong(),
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        } while (System.currentTimeMillis() < endTime)

        val debugMessage = buildString {
            appendLine("Could not find a visible element matching selector: ${command.selector.description()}")
            appendLine("Tip: Try adjusting the following settings to improve detection:")
            appendLine("- `timeout`: current = ${command.timeout}ms → Increase if you need more time to find the element")
            val originalSpeed = command.originalSpeedValue?.toIntOrNull()
            val speedAdvice = if (originalSpeed != null && originalSpeed > 50) {
                "Reduce for slower, more precise scrolling to avoid overshooting elements"
            } else {
                "Increase for faster scrolling if element is far away"
            }
            appendLine("- `speed`: current = ${command.originalSpeedValue} (0-100 scale) → $speedAdvice")
            val waitSettleAdvice = if (command.waitToSettleTimeoutMs == null) {
                "Set this value (e.g., 500ms) if your UI updates frequently between scrolls"
            } else {
                "Increase if your UI needs more time to update between scrolls"
            }
            val waitToTimeSettleMessage = if (command.waitToSettleTimeoutMs != null) {
                "${command.waitToSettleTimeoutMs}ms"
            } else {
                "Not defined"
            }
            appendLine("- `waitToSettleTimeoutMs`: current = $waitToTimeSettleMessage → $waitSettleAdvice")
            appendLine("- `visibilityPercentage`: current = ${command.visibilityPercentage}% → Lower this value if you want to detect partially visible elements")
            val centerAdvice = if (command.centerElement) {
                "Disable if you don't need the element to be centered after finding it"
            } else {
                "Enable if you want the element to be centered after finding it"
            }
            appendLine("- `centerElement`: current = ${command.centerElement} → $centerAdvice")
        }
        throw MaestroException.ElementNotFound(
            message = "No visible element found: ${command.selector.description()}",
            maestro.viewHierarchy().root,
            debugMessage = debugMessage
        )
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

    // --- The sole evaluateCondition implementation (Task 1.8): promoted onto ExecutionBackend and the
    // flow-control guards (runScript/repeat/runFlow when:) now route here through the interface;
    // Orchestra's private copy was deleted. Reads the clock from context.timeMsOfLastInteraction. No JS
    // engine access: the scriptCondition value is already evaluated by this point (pure string checks).
    override suspend fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long?,
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
                val found = findElement(
                    selector = it,
                    timeoutMs = adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs, context.timeMsOfLastInteraction),
                    optional = commandOptional,
                    context = context,
                )
                // Capture the element the assertion matched for the differential trace — same
                // findElement call, its result was previously discarded. No extra device read.
                val center = found.element.bounds.center()
                lastChosenElement = chosenElementOf(found.element, center.x, center.y)
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

    // --- The sole findElement implementation (Task 1.8; made private in Task 4.0 — no longer on the
    // seam). All device callers (tap/swipe/copyText/scrollUntilVisible/evaluateCondition below, plus
    // the takeScreenshot crop path above) resolve selectors here; Orchestra's private copy and its
    // helpers (resolveParentHierarchy/buildFilter/childOfDebugMessage) were deleted. The timeout is
    // computed from context.timeMsOfLastInteraction (see adjustedToLatestInteraction below).
    private suspend fun findElement(
        selector: ElementSelector,
        optional: Boolean,
        timeoutMs: Long? = null,
        context: BackendContext,
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

        // Logger for scrollUntilVisible's relocated log lines; Orchestra's logger is
        // `LoggerFactory.getLogger(Orchestra::class.java)` — this is the backend's own instance.
        private val logger = LoggerFactory.getLogger(LegacyExecutionBackend::class.java)
    }
}
