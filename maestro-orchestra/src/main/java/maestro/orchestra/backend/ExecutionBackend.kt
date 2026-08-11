package maestro.orchestra.backend

import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroConfig
import maestro.Bounds
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.device.CapturedDeviceArtifact
import okio.Sink
import java.io.File

/**
 * The seam. Orchestra (router) dispatches device-touching commands here.
 * ABOVE this line: flow control, variables, artifacts, reporting (stay in Orchestra).
 * BELOW this line: selector resolution, synchronization/settle, retry, driver calls.
 */
interface ExecutionBackend {
    /**
     * Stable identifier for the backend that produced a step, surfaced into each trace record so the
     * Phase-5 differential/coverage report can tell the two runs apart. `"legacy"` for the legacy
     * backend, `"devicecore"` for device-core. The label is part of the trace schema — keep it stable.
     */
    val backendId: String

    /**
     * Provision + connect the driver for this run and apply per-run device config. appId = the flow's
     * app-under-test; [config] carries run config the backend needs at open time (e.g. legacy derives
     * the Android Chrome DevTools webview-hierarchy toggle from it). Called once at run start.
     */
    fun open(appId: String?, config: MaestroConfig?)

    /** Teardown. Called once at run end. */
    fun close()

    /**
     * Execute one already-resolved (variable-interpolated) device-touching command.
     * Returns whether the command mutated device state (preserves Orchestra.executeCommand's
     * Boolean semantics that drive timeMsOfLastInteraction) plus the per-step trace.
     */
    suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult

    /**
     * Snapshot the current view hierarchy root for artifacts/reporting above the seam. Nullable:
     * legacy returns its real tree; a backend with no serializable tree (device-core) returns null.
     */
    fun hierarchySnapshot(): TreeNode?

    /**
     * Capture a screenshot into [out] for an artifact/AI command above the seam. When [cropOn] is
     * non-null the backend resolves it against the live hierarchy BELOW the seam and crops the shot to
     * that element (so no selector-resolution type crosses the seam); [optional]/[context] feed that
     * lookup exactly as the router's own findElement did. On non-positive crop dimensions the backend
     * throws [InvalidCropDimensions] carrying the offending bounds for the router to re-wrap into the
     * command-specific assertion failure. With [cropOn] null this delegates verbatim to today's
     * `maestro.takeScreenshot` (no crop).
     */
    suspend fun takeScreenshot(
        out: Sink,
        compressed: Boolean,
        cropOn: ElementSelector? = null,
        optional: Boolean = false,
        context: BackendContext? = null,
    )

    /** Start a screen recording into [out], returning the handle the router closes at stopRecording. */
    suspend fun startScreenRecording(out: Sink): ScreenRecording

    /**
     * Evaluate a `when:`/assert [condition] (platform / already-evaluated script / visible /
     * notVisible) against the device. Pure device-condition evaluation with no JS-engine access —
     * the router evaluates scripts above the seam before calling this.
     */
    suspend fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long? = null,
        context: BackendContext,
    ): Boolean

    /**
     * Device-log + crash/ANR capture for the artifact bundle (reporting only — never part of the
     * per-step verdict). These are best-effort: a backend that can't capture returns nothing here and
     * the run is unaffected. Default = no-op / empty (the honest behavior for a backend without the
     * capability — e.g. device-core, which owes `deviceLog`/`crashArtifacts` per its ROADMAP). The
     * legacy backend overrides these to delegate to its `Maestro`.
     */
    suspend fun startDeviceLogCapture() {}
    suspend fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> = emptyList()
    suspend fun collectCrashArtifacts(appId: String?, sinceEpochMs: Long, outputDir: File): List<CapturedDeviceArtifact> = emptyList()
}

/**
 * Thrown BELOW the seam by [ExecutionBackend.takeScreenshot] when a resolved crop element has
 * non-positive width/height. Carries the offending [bounds] so the router can re-wrap it into the
 * command-specific `MaestroException.AssertionFailure` (with the right debugMessage) above the seam,
 * keeping the exact error text out of the backend and no selector-resolution type crossing the seam.
 */
class InvalidCropDimensions(val bounds: Bounds) : Exception()

/** Read-only per-command inputs the backend needs from the router (timeouts, flow config). */
data class BackendContext(
    val lookupTimeoutMs: Long,          // 17000 for legacy; ignored by device-core
    val optionalLookupTimeoutMs: Long,  // 7000 for legacy; ignored by device-core
    // Extended as handlers are relocated; keep additive.
    // The interaction clock stays owned by Orchestra; the router passes its current value in so the
    // relocated findElement can reproduce adjustedToLatestInteraction against the same clock.
    val timeMsOfLastInteraction: Long = System.currentTimeMillis(),
    // Flow config the relocated tap needs (config?.appId today); null when unknown.
    val appId: String? = null,
    // Clipboard value the router owns (Orchestra's copiedText). pasteText READS this; the backend
    // never owns the variable. Additive/defaulted so prior constructions keep compiling.
    val copiedText: String? = null,
)

data class CommandExecutionResult(
    val mutating: Boolean,              // == today's Orchestra.executeCommand Boolean return
    val trace: StepTrace? = null,       // Phase 1 leaves null; Phase 3 populates for the differential
    // Text a command extracted for the router to store (copyTextFrom's resolved text). The router
    // consumes this into copiedText; null for every other command. Additive/defaulted.
    val output: String? = null,
)

/**
 * The unified per-step diff record. Both backends populate it; the trace emitter consumes it. The
 * verdict itself is never carried here: Orchestra derives it from the lifecycle outcome (return →
 * PASS, thrown MaestroException → FAIL, other throwable → ERROR) — a backend that can't serve a
 * command throws (typically [BackendUnsupportedOperation]) instead of returning a trace.
 */
data class StepTrace(
    val chosenElement: ChosenElement? = null,  // null when the command resolves no element
    val evidence: Map<String, String?> = emptyMap(),  // backend-specific
)

enum class Verdict { PASS, FAIL, ERROR }

data class ChosenElement(
    val x: Int, val y: Int, val width: Int, val height: Int,  // bounds
    val centerX: Int, val centerY: Int,                        // the coordinate the gesture used
    val text: String?,
    val resourceId: String?,
    val index: Int?,                                           // selection index if one was used
)
