package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.debuglog.ScopedLogCapture
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Internal listener Orchestra always installs. Owns:
 *
 *   - In-memory population of [FlowDebugOutput] (status, timestamp, duration,
 *     error, sequenceNumber, evaluatedCommand). Always on — cheap, no I/O,
 *     consumers read it via `Orchestra.debugOutput`.
 *
 *   - When `artifactsDir` is non-null: produces the on-disk flow-debug
 *     bundle:
 *       `artifactsDir/maestro.log` — scoped capture of `maestro.*` loggers
 *       `artifactsDir/commands.json` — per-command metadata, with hierarchy
 *         inline on the failing command
 *       `artifactsDir/screenshot-❌-<unix-millis>.png` — auto-capture at the
 *         moment of a command failure
 *
 * On a failed command (with `artifactsDir != null`), hierarchy capture and
 * screenshot capture run in independent `try/catch` blocks — either failing
 * logs a warning and the other still proceeds.
 *
 * When `artifactsDir == null` (Studio's interactive runner today): no log
 * appender, no commands.json write, and the expensive failure-time device
 * round-trips for hierarchy / screenshot are skipped. In-memory population
 * still happens.
 *
 * Not part of the public API. Construction is owned by Orchestra; consumers
 * interact through Orchestra's `artifactsDir` param and read
 * `Orchestra.debugOutput` for in-memory state.
 */
internal class ArtifactsGenerator(
    private val artifactsDir: Path?,
    private val maestro: Maestro,
) : OrchestraListener {

    val debugOutput = FlowDebugOutput()
    private var logCapture: ScopedLogCapture? = null

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            artifactsDir.toFile().mkdirs()
            logCapture = ScopedLogCapture.start(artifactsDir.resolve("maestro.log").toFile())
        } catch (e: Exception) {
            logger.warn("Failed to set up artifacts directory at $artifactsDir", e)
        }
    }

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) {
        debugOutput.commands[cmd] = CommandDebugMetadata(
            timestamp = System.currentTimeMillis(),
            status = CommandStatus.RUNNING,
            sequenceNumber = sequenceNumber,
        )
    }

    override fun onCommandFinished(
        cmd: MaestroCommand,
        outcome: CommandOutcome,
        startedAt: Long,
        finishedAt: Long,
    ) {
        val metadata = debugOutput.commands.getOrPut(cmd) {
            CommandDebugMetadata(timestamp = startedAt)
        }
        metadata.status = outcome.toCommandStatus()
        metadata.duration = finishedAt - startedAt

        if (outcome is CommandOutcome.Failed) {
            metadata.error = outcome.error
            if (outcome.error is MaestroException) {
                debugOutput.exception = outcome.error
            }
            // Failure-time device round-trips are expensive; gate them on
            // having a bundle to produce. Independent best-effort: hierarchy
            // capture and screenshot capture do not gate each other.
            if (artifactsDir != null) {
                captureHierarchy(metadata)
                captureFailureScreenshot()
            }
        }
    }

    override fun onCommandReset(cmd: MaestroCommand) {
        debugOutput.commands[cmd]?.let { it.status = CommandStatus.PENDING }
    }

    override fun onCommandMetadataUpdate(cmd: MaestroCommand, metadata: Orchestra.CommandMetadata) {
        debugOutput.commands[cmd]?.let { existing ->
            existing.evaluatedCommand = metadata.evaluatedCommand
        }
    }

    override fun onFlowEnd() {
        if (artifactsDir != null) {
            try {
                TestOutputWriter.saveCommands(
                    path = artifactsDir,
                    debugOutput = debugOutput,
                    commandsFilename = "commands.json",
                )
            } catch (e: Exception) {
                logger.warn("Failed to write commands.json under $artifactsDir", e)
            }
        }
        try {
            logCapture?.close()
        } catch (e: Exception) {
            logger.warn("Failed to close scoped log capture", e)
        } finally {
            logCapture = null
        }
    }

    private fun captureHierarchy(metadata: CommandDebugMetadata) {
        try {
            val tree = runBlocking { maestro.viewHierarchy() }.root
            metadata.hierarchy = tree
        } catch (e: Exception) {
            logger.warn("Failed to capture view hierarchy on command failure", e)
        }
    }

    private fun captureFailureScreenshot() {
        if (artifactsDir == null) return
        try {
            val destFile = File(
                artifactsDir.toFile(),
                "screenshot-❌-${System.currentTimeMillis()}.png",
            )
            ScreenshotUtils.takeDebugScreenshot(
                maestro = maestro,
                debugOutput = debugOutput,
                status = CommandStatus.FAILED,
                destFile = destFile,
            )
        } catch (e: Exception) {
            logger.warn("Failed to capture failure screenshot", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactsGenerator::class.java)
    }
}
