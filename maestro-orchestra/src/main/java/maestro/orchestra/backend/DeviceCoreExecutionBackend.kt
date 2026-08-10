package maestro.orchestra.backend

import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.android.AndroidDeviceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroConfig
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.devicecore.AssertVisibleVerdict
import maestro.orchestra.devicecore.DeviceCoreRouting
import maestro.orchestra.devicecore.DeviceCoreUnavailable
import maestro.orchestra.devicecore.RoutedQuery
import okio.Sink
import org.slf4j.LoggerFactory

/**
 * A minimal, Android-first [ExecutionBackend] built on maestro-device-core's
 * `connect → screen → getBy* → tap/inspect` API. It serves only a few verbs — assertVisible /
 * assertNotVisible on a literal-text selector, and tapOn a literal-id selector — and DECLINES
 * everything else cleanly (a declined step is a logged coverage gap the router re-runs on legacy,
 * never a crash and never a failure).
 *
 * NOT wired for run-time selection yet: this task is the backend + its build wiring + unit tests only.
 * It is unit-tested against a fake [DeviceProvider]; no real device is involved.
 */
class DeviceCoreExecutionBackend(
    private val appId: String?,
    private val providerFactory: () -> DeviceProvider = { AndroidDeviceProvider() },
) : ExecutionBackend {

    private val logger = LoggerFactory.getLogger(DeviceCoreExecutionBackend::class.java)

    override val backendId: String = "devicecore"

    private var device: Device? = null

    /**
     * Connect the device-core driver once for this run. device-core's Android locate path
     * (UiAutomation) queries the whole screen regardless of the app-under-test, so — unlike the iOS
     * prototype, which set a process-global `devicecore.ios.bundleId` for XCUI to snapshot the right
     * app — there is NO per-app binding to apply here. We hold [appId] on the instance for parity and
     * future use and set no global property. [config] (the legacy Android-webview toggle) is ignored;
     * there is no find-loop and no settle.
     */
    override fun open(appId: String?, config: MaestroConfig?) {
        device = runBlocking { providerFactory().connect(TargetSelector(TargetId.ANDROID_EMU)) }
    }

    /** Close the device-core [Device] (stops the server). Null-safe if [open] was never called. */
    override fun close() {
        device?.close()
        device = null
    }

    override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult =
        when (command) {
            is AssertConditionCommand -> executeAssert(command)
            is TapOnElementCommand -> executeTap(command)
            else -> declined("device-core has no verb for ${command::class.simpleName}")
        }

    private suspend fun executeAssert(command: AssertConditionCommand): CommandExecutionResult {
        val query = DeviceCoreRouting.route(command.condition)
            ?: return declined("non-routable assert condition: ${command.condition.description()}")
        // A DeviceCoreUnavailable from inspect() is an infra failure: let it propagate. It is not a
        // MaestroException, so Orchestra's lifecycle maps it to ERROR (not FAIL) — the router's cue to
        // re-run the step on legacy rather than fail the flow.
        val evidence = inspect(query)
        val pass = AssertVisibleVerdict.pass(evidence, query.mode)
        // Orchestra derives the verdict from the lifecycle, never from a returned StepTrace.verdict: a
        // normal return is PASS, a thrown MaestroException is FAIL. So a failed assert must THROW (like
        // legacy at LegacyExecutionBackend.assertConditionCommand), not return a FAIL trace — otherwise
        // Orchestra reads the return as PASS and the failed assert silently passes.
        if (!pass) {
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${command.condition.description()}",
                hierarchyRoot = null, // device-core has no serializable view tree
                debugMessage = "device-core assert failed: ${command.condition.description()}",
            )
        }
        return CommandExecutionResult(
            mutating = false,
            trace = StepTrace(chosenElement = chosenElementOf(evidence)),
        )
    }

    private suspend fun executeTap(command: TapOnElementCommand): CommandExecutionResult {
        // device-core's plain `.tap()` is a single centered tap; it can't faithfully serve a
        // long-press, a repeat (double-/multi-tap), or an element-relative point. These are
        // command-level fields the selector-only routability check can't see, so guard them here —
        // otherwise a modified gesture would silently downgrade to a plain tap reported as success.
        if (command.longPress == true || command.repeat != null || command.relativePoint != null) {
            return declined("device-core tap has no long-press/repeat/relative-point verb: ${command.selector.description()}")
        }
        val id = DeviceCoreRouting.routeIdTap(command.selector)
            ?: return declined("non-routable tap selector: ${command.selector.description()}")
        val action = try {
            requireDevice().screen.getById(id).tap()
        } catch (e: DeviceCoreUnavailable) {
            // Infra failure: propagate so Orchestra's lifecycle maps it to ERROR (non-MaestroException).
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed tap (element not found / gesture rejected) is a FAIL, matching legacy's
            // ElementNotFound throw. A thrown MaestroException makes Orchestra derive FAIL; a returned
            // trace would be read as PASS and the failed tap would silently pass.
            throw MaestroException.ElementNotFound(
                message = "No visible element found: ${command.selector.description()}",
                hierarchyRoot = null, // device-core has no serializable view tree
                debugMessage = "device-core tap() failed for id '$id': ${e.message}",
            )
        }
        val point = action.injectPoint
        return CommandExecutionResult(
            mutating = true,
            trace = StepTrace(
                chosenElement = ChosenElement(
                    x = 0, y = 0, width = 0, height = 0,
                    centerX = point?.x ?: 0, centerY = point?.y ?: 0,
                    text = null, resourceId = id, index = null,
                ),
            ),
        )
    }

    /**
     * Evaluate a bare `visible`/`notVisible` literal-text condition via the same inspect + verdict
     * path. A non-routable / platform / script condition is out of device-core's scope: this stub
     * returns a safe non-blocking `true` (a later task refines selection so device-core only ever
     * receives routable conditions). A [DeviceCoreUnavailable] from inspect propagates as an infra
     * failure — the router handles it above the seam.
     */
    override suspend fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long?,
        context: BackendContext,
    ): Boolean {
        if (condition == null) return true
        val query = DeviceCoreRouting.route(condition) ?: return true
        val evidence = inspect(query)
        return AssertVisibleVerdict.pass(evidence, query.mode)
    }

    /** device-core has no serializable view tree. */
    override fun hierarchySnapshot(): TreeNode? = null

    override suspend fun takeScreenshot(
        out: Sink,
        compressed: Boolean,
        cropOn: ElementSelector?,
        optional: Boolean,
        context: BackendContext?,
    ) {
        throw BackendUnsupportedOperation("device-core has no screenshot/recording verb")
    }

    override suspend fun startScreenRecording(out: Sink): ScreenRecording {
        throw BackendUnsupportedOperation("device-core has no screenshot/recording verb")
    }

    // --- internals ---

    private suspend fun inspect(query: RoutedQuery): ElementEvidence {
        val screen = requireDevice().screen
        val base: Locator = screen.getByText(query.text, query.match)
        val locator = query.index?.let { base.nth(it) } ?: base
        return try {
            locator.inspect()
        } catch (e: DeviceCoreUnavailable) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeviceCoreUnavailable("device-core inspect() failed for '${query.text}': ${e.message}")
        }
    }

    private fun declined(reason: String): CommandExecutionResult {
        logger.info("device-core declined: {}", reason)
        // A declined step is neither a pass nor a fail — it's a coverage gap the router re-runs on
        // legacy. It must not register as a failure, so the verdict is PASS with declined = true.
        return CommandExecutionResult(
            mutating = false,
            trace = StepTrace(verdict = Verdict.PASS, chosenElement = null, declined = true, declinedReason = reason),
        )
    }

    private fun chosenElementOf(evidence: ElementEvidence): ChosenElement? {
        if (evidence.resolution !is Resolution.Resolved) return null
        val rect = evidence.bounds.value ?: return null
        return ChosenElement(
            x = rect.x, y = rect.y, width = rect.width, height = rect.height,
            centerX = rect.x + rect.width / 2, centerY = rect.y + rect.height / 2,
            text = evidence.target, resourceId = null, index = null,
        )
    }

    private fun requireDevice(): Device =
        device ?: error("DeviceCoreExecutionBackend.open() must be called before use")
}
