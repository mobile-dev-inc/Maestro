package maestro.orchestra.backend

import maestro.orchestra.Command
import maestro.ViewHierarchy
import maestro.DeviceInfo

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

    /** Snapshot the current view hierarchy for artifacts/reporting above the seam. */
    fun viewHierarchy(excludeKeyboardElements: Boolean = false): ViewHierarchy

    val deviceInfo: DeviceInfo
}

/** Read-only per-command inputs the backend needs from the router (timeouts, flow config). */
data class BackendContext(
    val lookupTimeoutMs: Long,          // 17000 for legacy; ignored by device-core
    val optionalLookupTimeoutMs: Long,  // 7000 for legacy; ignored by device-core
)

data class CommandExecutionResult(
    val mutating: Boolean,              // == today's Orchestra.executeCommand Boolean return
    val trace: StepTrace? = null,       // Phase 1 leaves null; Phase 3 populates for the differential
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
