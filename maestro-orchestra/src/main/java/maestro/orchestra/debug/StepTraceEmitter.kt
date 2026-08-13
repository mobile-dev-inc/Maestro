package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.Verdict
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File

/**
 * Writes one JSONL record per executed step at the frozen differential-gate schema (Spec C consumes
 * it unchanged). Reads only data the step already produced — never touches the device. device-core
 * has no decline path, so the WIP's declined/declinedReason fields are dropped; NON_NULL keeps the
 * record shape identical to the legacy schema for every step.
 */
class StepTraceEmitter(
    private val traceFile: File,
    private val backendId: String = "devicecore",
) {
    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private var writer: BufferedWriter? = null

    fun openFor() {
        try {
            traceFile.parentFile?.mkdirs()
            writer = traceFile.bufferedWriter()
        } catch (e: Exception) {
            logger.warn("Failed to open step trace file at $traceFile", e)
            writer = null
        }
    }

    fun close() {
        try { writer?.flush(); writer?.close() }
        catch (e: Exception) { logger.warn("Failed to close step trace file at $traceFile", e) }
        finally { writer = null }
    }

    fun emit(
        stepIndex: Int,
        commandType: String,
        selectorText: String?,
        selectorId: String?,
        verdict: Verdict,
        chosen: ChosenElement?,
    ) {
        val w = writer ?: return
        val record = StepTraceRecord(
            stepIndex = stepIndex,
            backendId = backendId,
            command = CommandDescriptor(commandType, selectorText, selectorId),
            verdict = verdict.name,
            chosenElement = chosen,
        )
        try { w.write(mapper.writeValueAsString(record)); w.newLine(); w.flush() }
        catch (e: Exception) { logger.warn("Failed to write step trace record for step $stepIndex", e) }
    }

    private data class StepTraceRecord(
        val stepIndex: Int,
        val backendId: String,
        val command: CommandDescriptor,
        val verdict: String,
        val chosenElement: ChosenElement?,
    )

    private data class CommandDescriptor(
        val type: String,
        val selectorText: String?,
        val selectorId: String?,
    )

    companion object { private val logger = LoggerFactory.getLogger(StepTraceEmitter::class.java) }
}
