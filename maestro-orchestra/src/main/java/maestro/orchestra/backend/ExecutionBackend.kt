package maestro.orchestra.backend

import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.Bounds
import maestro.FindElementResult
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.ViewHierarchy
import okio.Sink

/**
 * The seam. Orchestra (router) dispatches device-touching commands here.
 * ABOVE this line: flow control, variables, artifacts, reporting (stay in Orchestra).
 * BELOW this line: selector resolution, synchronization/settle, retry, driver calls.
 */
interface ExecutionBackend {
    /** Provision + connect the driver for this run. appId = the flow's app-under-test. Called once at run start. */
    fun open(appId: String?)

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
     * Capture a screenshot into [out] for an artifact/AI command above the seam. Delegates verbatim
     * to today's `maestro.takeScreenshot`; [bounds] (grid units) crops the shot when non-null.
     */
    suspend fun takeScreenshot(out: Sink, compressed: Boolean, bounds: Bounds? = null)

    /** Start a screen recording into [out], returning the handle the router closes at stopRecording. */
    suspend fun startScreenRecording(out: Sink): ScreenRecording

    /** Toggle Android Chrome DevTools-backed webview hierarchy for this run (config/setup step). */
    fun setAndroidChromeDevToolsEnabled(enabled: Boolean)

    /**
     * Resolve [selector] against the live hierarchy. The sole selector-resolution implementation
     * lives below the seam; the router calls this for its flow-control guards and screenshot crops.
     * [context] carries the interaction clock (Orchestra still owns/snapshots it) so the lookup
     * window reproduces adjustedToLatestInteraction against the same clock.
     */
    suspend fun findElement(
        selector: ElementSelector,
        optional: Boolean,
        timeoutMs: Long? = null,
        context: BackendContext,
    ): FindElementResult

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
}

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

/** The unified per-step diff record. Both backends populate it; the trace emitter consumes it. */
data class StepTrace(
    val verdict: Verdict,               // PASS / FAIL / ERROR
    val chosenElement: ChosenElement?,  // null when the command resolves no element
    val declined: Boolean = false,      // device-core: command not implemented -> logged coverage gap
    val declinedReason: String? = null,
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
