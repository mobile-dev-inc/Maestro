package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.MaestroCommand
import maestro.orchestra.backend.ChosenElement
import maestro.orchestra.backend.StepTrace
import maestro.orchestra.backend.Verdict
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File

/**
 * Behavior-neutral instrument for the zero-divergence differential gate. It writes one JSONL record
 * per executed step — verdict + chosen element + coordinates — so the gate can diff the refactor
 * branch against stock Maestro without either run interpreting anything at runtime.
 *
 * It reads ONLY data the command already produced: the [StepTrace] the backend returns (which the
 * router stashes and hands here) plus the lifecycle outcome. It NEVER touches the device — no
 * findElement, no viewHierarchy, no screenshot. That is the whole point: the emitter must not perturb
 * find/settle/retry/dispatch timing, or the gate it feeds would be diffing its own interference.
 *
 * Off by default: Orchestra only constructs and registers it when the run opts in
 * (`MAESTRO_STEP_TRACE=1`, or an explicit emitter passed to Orchestra). When absent, zero behavior
 * change and no file written.
 *
 * ## Trace JSON schema (the gate's diff contract — keep field names stable)
 * One JSON object per line ([BundleLayout.STEP_TRACE] under the artifacts bundle):
 * ```
 * {
 *   "stepIndex": 0,                       // sequenceNumber — monotonic across the flow, nested included
 *   "backendId": "legacy",                // which backend produced the step
 *   "command": {
 *     "type": "TapOnElementCommand",      // MaestroCommand::asCommand simpleName
 *     "selectorText": "Login",            // primary selector's textRegex, or absent
 *     "selectorId": "com.app:id/login"    // primary selector's idRegex, or absent
 *   },
 *   "verdict": "PASS",                    // PASS | FAIL | ERROR — from the lifecycle outcome, not a device read
 *   "chosenElement": {                    // absent when the command resolved no element
 *     "x": 10, "y": 20, "width": 100, "height": 40,   // resolved element bounds
 *     "centerX": 60, "centerY": 40,                    // the coordinate the gesture actually used
 *     "text": "Login",                                 // element text attribute, or absent
 *     "resourceId": "com.app:id/login",                // element resource-id attribute, or absent
 *     "index": null                                    // selection index if the selector used one
 *   },
 *   "error": {                            // present ONLY when the outcome captured a Throwable — a
 *                                          // thrown MaestroException (verdict FAIL) or any other
 *                                          // throwable (verdict ERROR). Absent on PASS, and absent on
 *                                          // a FAIL from CommandOutcome.Warned (an optional command
 *                                          // that failed carries no Throwable, verdict FAIL, no error).
 *     "type": "BackendUnsupportedOperation",           // the outcome error's ::class.simpleName
 *     "message": "device-core has no verb for ..."     // the outcome error's message, or absent
 *   }
 * }
 * ```
 * `error` is the harness's sole signal for telling gap (`BackendUnsupportedOperation`) from infra
 * (`DeviceCoreUnavailable`) from divergence (verdict FAIL with an error) apart — it is derived purely
 * from the lifecycle outcome ([maestro.orchestra.debug.CommandOutcome.Failed.error]), never from a
 * backend field.
 * Sibling task ports this same schema onto stock main; the record shape here is the contract.
 */
class StepTraceEmitter(
    private val traceFile: File,
    private val backendId: String = "legacy",
) : OrchestraListener {

    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private var writer: BufferedWriter? = null

    override fun onFlowStart() {
        try {
            traceFile.parentFile?.mkdirs()
            writer = traceFile.bufferedWriter()
        } catch (e: Exception) {
            logger.warn("Failed to open step trace file at $traceFile", e)
            writer = null
        }
    }

    override fun onFlowEnd() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close step trace file at $traceFile", e)
        } finally {
            writer = null
        }
    }

    /**
     * Append one step's record. Called by the router with the [StepTrace] the backend produced for
     * this step (null for a command that resolved no element), the [Verdict] derived from the
     * lifecycle outcome, and — for a FAIL/ERROR step — the [error] throwable the outcome carried
     * ([maestro.orchestra.debug.CommandOutcome.Failed.error]; null for PASS/Warned/Skipped). Never
     * reads the device.
     */
    fun emit(stepIndex: Int, command: MaestroCommand, verdict: Verdict, trace: StepTrace?, error: Throwable? = null) {
        val w = writer ?: return
        val selector = command.elementSelector()
        val record = StepTraceRecord(
            stepIndex = stepIndex,
            backendId = backendId,
            command = CommandDescriptor(
                type = command.asCommand()?.let { it::class.simpleName } ?: "Unknown",
                selectorText = selector?.textRegex,
                selectorId = selector?.idRegex,
            ),
            verdict = verdict.name,
            chosenElement = trace?.chosenElement,
            // Emit `error` ONLY for a FAIL/ERROR step. NON_NULL serialization omits it otherwise, so
            // legacy traces — legacy never errors on the corpus — stay byte-identical to the
            // pre-existing schema (the Phase-2 gate contract).
            error = error?.let { ErrorDescriptor(type = it::class.simpleName ?: "Unknown", message = it.message) },
        )
        try {
            w.write(mapper.writeValueAsString(record))
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            logger.warn("Failed to write step trace record for step $stepIndex", e)
        }
    }

    private data class StepTraceRecord(
        val stepIndex: Int,
        val backendId: String,
        val command: CommandDescriptor,
        val verdict: String,
        val chosenElement: ChosenElement?,
        // Present only for a FAIL/ERROR step; NON_NULL omits it for every PASS step.
        val error: ErrorDescriptor? = null,
    )

    /** Type + message of the outcome error that failed a step — the harness's gap/infra/divergence signal. */
    data class ErrorDescriptor(
        val type: String,
        val message: String?,
    )

    private data class CommandDescriptor(
        val type: String,
        val selectorText: String?,
        val selectorId: String?,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(StepTraceEmitter::class.java)
    }
}
