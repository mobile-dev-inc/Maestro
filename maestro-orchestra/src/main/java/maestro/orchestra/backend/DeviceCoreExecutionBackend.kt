package maestro.orchestra.backend

import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.AppId
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceEnvError
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.android.AndroidDeviceProvider
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.device.CapturedDeviceArtifact
import maestro.device.Platform
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.devicecore.AssertVisibleVerdict
import maestro.orchestra.devicecore.DeviceCoreRouting
import maestro.orchestra.devicecore.DeviceCoreUnavailable
import maestro.orchestra.devicecore.RoutedQuery
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A minimal, platform-parametric [ExecutionBackend] built on maestro-device-core's
 * `connect → screen → getBy* → tap/inspect` API. It serves only a few verbs — assertVisible /
 * assertNotVisible on a literal-text selector, and tapOn a literal-id selector — and hard-fails
 * everything else with a typed exception (never a silent "worked" for a command it can't honor): a
 * not-yet-built verb/selector/modifier throws [BackendUnsupportedOperation] (a coverage gap — Orchestra's
 * lifecycle maps that to ERROR), a genuinely failed assertion/tap throws a [MaestroException] (FAIL),
 * and a transport/driver failure throws [maestro.orchestra.devicecore.DeviceCoreUnavailable] (infra-only
 * ERROR).
 *
 * [platform] picks the device-core peer: Android → [AndroidDeviceProvider] / [TargetId.ANDROID_EMU],
 * iOS → [IosDeviceProvider] / [TargetId.IOS_SIM]. Neither platform needs a Maestro-side app binding:
 * device-core resolves the foreground app on-device for its queries (iOS as of device-core #133, which
 * retired the `devicecore.ios.bundleId` process global), and the app-under-test is named per-verb by
 * wrapping the id in an [AppId] (e.g. launchApp). Wired for runtime selection: [ExecutionBackendFactory]
 * routes to this backend when `MAESTRO_DEVICECORE_ASSERT=1`. It is unit-tested against a fake
 * [DeviceProvider]; no real device is involved.
 *
 * [deviceSerial] is the already-selected Maestro session's device serial (Android) / udid (iOS), when
 * known — threaded into [TargetSelector.serial] so device-core's own Android capabilities
 * (`resolveSerial()` in `AndroidLaunchAppStrategies`/`AndroidStopAppStrategies`/
 * `AndroidOpenLinkStrategies`) target that exact device instead of falling back to "the single
 * attached adb device," which throws on any host with more than one. Null (the default) reproduces
 * the pre-existing behavior exactly — single-device hosts, or callers that don't have a serial yet,
 * are unaffected.
 */
class DeviceCoreExecutionBackend(
    private val platform: Platform,
    private val appId: String?,
    private val deviceSerial: String? = null,
    private val providerFactory: () -> DeviceProvider = defaultProviderFor(platform),
) : ExecutionBackend {

    private val logger = LoggerFactory.getLogger(DeviceCoreExecutionBackend::class.java)

    override val backendId: String = "devicecore"

    private var device: Device? = null

    private val targetSelector: TargetSelector
        get() = when (platform) {
            Platform.ANDROID -> TargetSelector(TargetId.ANDROID_EMU, serial = deviceSerial)
            Platform.IOS -> TargetSelector(TargetId.IOS_SIM, serial = deviceSerial)
            else -> error("device-core does not support platform $platform")
        }

    companion object {
        private fun defaultProviderFor(platform: Platform): () -> DeviceProvider = when (platform) {
            Platform.ANDROID -> { { AndroidDeviceProvider() } }
            Platform.IOS -> { { IosDeviceProvider() } }
            else -> error("device-core does not support platform $platform")
        }
    }

    /**
     * Connect the device-core driver once for this run. device-core resolves the foreground app on
     * both platforms for its own queries — Android's UiAutomation reads the whole screen, and iOS
     * resolves the foreground app on-device (device-core #133) — so there is NO Maestro-side app
     * binding to apply here on either platform. The app-under-test reaches device-core per-verb (an
     * [AppId] on launchApp), not as a connect-time global. [appId]/[config] (the legacy Android-webview
     * toggle) are ignored; there is no find-loop and no settle.
     */
    override fun open(appId: String?, config: MaestroConfig?) {
        device = runBlocking { providerFactory().connect(targetSelector) }
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
            is LaunchAppCommand -> executeLaunch(command)
            else -> throw BackendUnsupportedOperation("device-core has no verb for ${command::class.simpleName}")
        }

    private suspend fun executeAssert(command: AssertConditionCommand): CommandExecutionResult {
        val query = DeviceCoreRouting.route(command.condition)
            ?: throw BackendUnsupportedOperation("device-core can't serve assert condition: ${command.condition.description()}")
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
            throw BackendUnsupportedOperation("device-core tap can't honor long-press/repeat/relative-point: ${command.selector.description()}")
        }
        val id = DeviceCoreRouting.routeIdTap(command.selector)
            ?: throw BackendUnsupportedOperation("device-core tap can't serve selector: ${command.selector.description()}")
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

    private suspend fun executeLaunch(command: LaunchAppCommand): CommandExecutionResult {
        // device-core.launchApp(appId) honors no modifiers. Hard-fail any launch that asks for more
        // than a plain "bring the app up (stopping it first if running)" — otherwise a modified launch
        // would silently downgrade to a bare one reported as success. (stopApp defaults to true/null,
        // which device-core's amForceStop+am start already does; stopApp == false it cannot honor.)
        if (command.clearState == true ||
            command.clearKeychain == true ||
            command.permissions != null ||
            !command.launchArguments.isNullOrEmpty() ||
            command.stopApp == false
        ) {
            throw BackendUnsupportedOperation("device-core launchApp can't honor its modifiers (clearState/clearKeychain/permissions/launchArguments/stopApp=false): ${command.appId}")
        }
        // launchApp is a device-core device-env verb: on failure it throws a typed DeviceEnvError
        // (decision 0011), consumed directly here instead of guessing from a catch-all. [mapDeviceEnvError]
        // folds its three mechanism-neutral arms onto this backend's taxonomy. InjectionUnavailable is
        // still thrown by strategy *selection* on an unmet precondition (its KDoc) -> infra ERROR.
        // CancellationException must propagate for cooperative cancellation. No catch-all remains: the old
        // one existed only to type device-core's untyped "expected exactly one adb device"
        // IllegalStateException, which device-core #139 fixed at the source (serial resolved once at
        // connect(), the multi-device case now typed there as DeviceResolutionFailure) — so a launch that
        // reaches here throws only its documented types.
        try {
            requireDevice().launchApp(AppId(command.appId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceEnvError) {
            throw mapDeviceEnvError("launchApp", command.appId, e)
        } catch (e: InjectionUnavailable) {
            throw DeviceCoreUnavailable("device-core launchApp precondition unmet for '${command.appId}': ${e.message}")
        }
        return CommandExecutionResult(
            mutating = true,                       // launching changes device/app state
            trace = StepTrace(chosenElement = null), // a launch resolves no element
        )
    }

    /**
     * Evaluate a bare `visible`/`notVisible` literal-text condition via the same inspect + verdict
     * path. A non-null, non-routable condition (platform / script / anything [DeviceCoreRouting] can't
     * route) is a coverage gap, not a benign pass: THROWS [BackendUnsupportedOperation] — no fabricated
     * guard verdicts, symmetric with the command path. (DECISION FLAG, Task K2: this makes a `when:`
     * guard device-core can't route hard-fail the flow; if benign `platform:` guards turn out common on
     * device-core runs this may need a targeted carve-out — one-line change to flip back to `true`.) A
     * [DeviceCoreUnavailable] from inspect propagates as an infra failure — the router handles it above
     * the seam.
     */
    override suspend fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long?,
        context: BackendContext,
    ): Boolean {
        if (condition == null) return true
        val query = DeviceCoreRouting.route(condition)
            ?: throw BackendUnsupportedOperation("device-core can't serve when/assert condition: ${condition.description()}")
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

    private val owedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun logOwed(capability: String) {
        if (owedLogged.add(capability)) {
            logger.info("device-core does not yet capture {} (owed; ROADMAP deviceLog/crashArtifacts) — no artifact produced", capability)
        }
    }

    override suspend fun startDeviceLogCapture() { logOwed("device log") }
    override suspend fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> { logOwed("device log"); return emptyList() }
    override suspend fun collectCrashArtifacts(appId: String?, sinceEpochMs: Long, outputDir: File): List<CapturedDeviceArtifact> { logOwed("crash/ANR"); return emptyList() }

    // --- internals ---

    /**
     * Fold device-core's typed [DeviceEnvError] (decision 0011 — a mechanism-neutral failure carrier
     * for the device-env verbs launchApp/openLink/stopApp) onto this backend's three-bucket taxonomy.
     * The `when` is exhaustive over the sealed error, so a future arm won't compile until it's bucketed:
     *  - [DeviceEnvError.UnsupportedOnTarget] -> [BackendUnsupportedOperation] (coverage gap -> ERROR):
     *    device-core has no realization of this verb on this target.
     *  - [DeviceEnvError.TransportFailure]    -> [DeviceCoreUnavailable] (infra -> ERROR): the device was
     *    unreachable (adb down, no booted sim, spawn failure).
     *  - [DeviceEnvError.OperationFailed]     -> [DeviceCoreUnavailable] (infra -> ERROR): device-core's
     *    quick strategy reached its mechanism and it reported failure. Bucketed as infra (re-run on
     *    legacy), NOT a flow FAIL — a device-env verb failing to complete is device-core being unable to
     *    serve the step, not a wrong-answer divergence from the oracle (only assert/tap verdicts diverge).
     */
    private fun mapDeviceEnvError(verb: String, appId: String, e: DeviceEnvError): RuntimeException = when (e) {
        is DeviceEnvError.UnsupportedOnTarget ->
            BackendUnsupportedOperation("device-core has no $verb realization for '$appId': ${e.message}")
        is DeviceEnvError.TransportFailure ->
            DeviceCoreUnavailable("device-core $verb transport failure for '$appId': ${e.message}")
        is DeviceEnvError.OperationFailed ->
            DeviceCoreUnavailable("device-core $verb failed for '$appId': ${e.message}")
    }

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

    private fun chosenElementOf(evidence: ElementEvidence): ChosenElement? {
        if (evidence.resolution !is Resolution.Resolved) return null
        val rect = evidence.bounds.value ?: return null
        return ChosenElement(
            x = rect.x, y = rect.y, width = rect.width, height = rect.height,
            centerX = rect.x + rect.width / 2, centerY = rect.y + rect.height / 2,
            // The resolved element's OWN text (whichever TextChannel device-core actually matched
            // on) — NOT evidence.target, which is the query descriptor (e.g.
            // "text=Notifications(EXACT)"). Writing the descriptor here made every device-core
            // assertVisible/tapOn step false-DIVERGE against legacy on element identity even when
            // both backends resolved the exact same element (fidelity-run-report.md finding #5).
            // `matched` is UNAVAILABLE for a channel-less selector (id/point) or any non-Resolved
            // arm, in which case this stays null rather than falling back to the descriptor.
            text = evidence.matched.value?.text,
            // device-core's ElementEvidence carries no resource-id (Android view id / iOS
            // accessibility identifier) today — there is no field on the API to read one from, so
            // this is left null rather than fabricated. (The harness's element-identity check is
            // being updated separately to not penalize a backend for a field it genuinely can't
            // emit yet.)
            resourceId = null, index = null,
        )
    }

    private fun requireDevice(): Device =
        device ?: error("DeviceCoreExecutionBackend.open() must be called before use")
}
