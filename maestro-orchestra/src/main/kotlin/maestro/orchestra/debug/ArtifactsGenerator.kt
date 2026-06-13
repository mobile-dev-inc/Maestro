package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.debuglog.ScopedLogCapture
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactFiles
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import okio.sink
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Internal listener Orchestra always installs. Populates [FlowDebugOutput]
 * in memory (always on; consumers read it via `Orchestra.debugOutput`) and,
 * when [artifactsDir] is non-null, writes the per-flow artifact bundle
 * directly under it — see [ArtifactFiles] for the layout. With a null
 * [artifactsDir] (Studio's interactive runner) only the in-memory population
 * happens. The full artifact set — per-step screenshots + the full-run recording
 * — is gated by [captureFullArtifacts] (worker, not the CLI); off, only the
 * failure screenshot is captured.
 *
 * Every file is routed through an [ArtifactCollector]: the manifest is the
 * collector's records and each command's artifact list is the same records
 * grouped by command, so the two can never disagree.
 */
internal class ArtifactsGenerator(
    private val artifactsDir: Path?,
    private val maestro: Maestro,
    private val captureFullArtifacts: Boolean = false,
) : OrchestraListener {

    val debugOutput = FlowDebugOutput()

    /** The run's artifact manifest; populated at [onFlowEnd], empty when [artifactsDir] is null. */
    var artifactManifest: ArtifactManifest = ArtifactManifest()
        private set
    private var collector: ArtifactCollector? = null
    private var logCapture: ScopedLogCapture? = null
    private var fullRunRecording: ScreenRecording? = null
    /**
     * Artifacts are emitted synchronously by the currently-executing leaf
     * command, so a single reference (no stack) is enough to attribute them.
     */
    private var currentCommandMetadata: CommandDebugMetadata? = null

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            val collector = ArtifactCollector(artifactsDir).also { this.collector = it }
            val logFile = collector.allocate(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, ArtifactFiles.MAESTRO_LOG)
            logCapture = ScopedLogCapture.start(logFile)
        } catch (e: Exception) {
            logger.warn("Failed to set up artifacts directory at $artifactsDir", e)
        }
        if (captureFullArtifacts) startFullRunRecording()
    }

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) {
        debugOutput.commands[cmd] = CommandDebugMetadata(
            timestamp = System.currentTimeMillis(),
            status = CommandStatus.RUNNING,
            sequenceNumber = sequenceNumber,
            command = cmd,
        ).also { currentCommandMetadata = it }
    }

    override fun onCommandArtifact(kind: ArtifactKind, relativePath: String) {
        val collector = collector ?: return
        val format = when (kind) {
            ArtifactKind.TAKE_SCREENSHOT -> ArtifactFormat.PNG
            ArtifactKind.START_SCREEN_RECORDING -> ArtifactFormat.MP4
            else -> null
        }
        collector.adopt(kind, relativePath, format, command = currentCommandMetadata?.command)
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
        }
        if (artifactsDir == null || outcome is CommandOutcome.Skipped) return

        captureStepHierarchy(metadata)
        if (outcome is CommandOutcome.Failed) {
            captureFailureScreenshot(metadata)
        } else if (captureFullArtifacts) {
            captureStepScreenshot(metadata)
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
        stopFullRunRecording()
        val collector = collector
        if (artifactsDir != null && collector != null) {
            // Each command's artifact list is its collector records — the same
            // source the manifest reads from, so commands.json can't drift.
            debugOutput.commands.values.forEach { meta ->
                meta.command?.let { cmd ->
                    meta.artifacts.clear()
                    meta.artifacts.addAll(collector.artifactsFor(cmd))
                }
            }
            try {
                TestOutputWriter.saveCommands(
                    path = artifactsDir,
                    debugOutput = debugOutput,
                    commandsFilename = ArtifactFiles.COMMANDS_JSON,
                )
                collector.adopt(ArtifactKind.COMMAND_METADATA, ArtifactFiles.COMMANDS_JSON, ArtifactFormat.JSON)
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

        if (artifactsDir != null && collector != null) {
            artifactManifest = collector.manifest()
            try {
                TestOutputWriter.saveManifest(artifactsDir, artifactManifest)
            } catch (e: Exception) {
                logger.warn("Failed to write manifest.json under $artifactsDir", e)
            }
        }
    }

    private fun captureStepHierarchy(metadata: CommandDebugMetadata) {
        val collector = collector ?: return
        try {
            val tree = runBlocking { maestro.viewHierarchy() }.root
            val destFile = collector.allocate(
                ArtifactKind.SCREEN_HIERARCHY,
                ArtifactFormat.JSON,
                "${ArtifactFiles.SCREEN_HIERARCHY_DIR}/step-${metadata.sequenceNumber}.json",
                command = metadata.command,
            )
            TestOutputWriter.bundleWriter.writeValue(destFile, tree)
        } catch (e: Exception) {
            logger.warn("Failed to capture step hierarchy", e)
        }
    }

    private fun captureFailureScreenshot(metadata: CommandDebugMetadata) {
        val collector = collector ?: return
        try {
            val destFile = collector.allocate(
                ArtifactKind.SCREENSHOT,
                ArtifactFormat.PNG,
                "${ArtifactFiles.STEP_SCREENSHOTS_DIR}/step-${metadata.sequenceNumber}${ArtifactFiles.SCREENSHOT_EXTENSION}",
                command = metadata.command,
            )
            // Null when capture failed or was deduped (parent composite after a
            // failed leaf). The file then never lands, so the collector drops the
            // record — attribution stays on the leaf, no extra bookkeeping here.
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

    private fun captureStepScreenshot(metadata: CommandDebugMetadata) {
        val collector = collector ?: return
        try {
            val destFile = collector.allocate(
                ArtifactKind.SCREENSHOT,
                ArtifactFormat.PNG,
                "${ArtifactFiles.STEP_SCREENSHOTS_DIR}/step-${metadata.sequenceNumber}${ArtifactFiles.SCREENSHOT_EXTENSION}",
                command = metadata.command,
            )
            runBlocking { maestro.takeScreenshot(destFile.sink(), false) }
        } catch (e: Exception) {
            logger.warn("Failed to capture per-step screenshot", e)
        }
    }

    private fun startFullRunRecording() {
        val collector = collector ?: return
        try {
            val destFile = collector.allocate(ArtifactKind.SCREEN_RECORDING, ArtifactFormat.MP4, ArtifactFiles.SCREEN_RECORDING)
            fullRunRecording = runBlocking { maestro.startScreenRecording(destFile.sink()) }
        } catch (e: Exception) {
            logger.warn("Failed to start full-run screen recording", e)
        }
    }

    private fun stopFullRunRecording() {
        try {
            fullRunRecording?.close()
        } catch (e: Exception) {
            logger.warn("Failed to stop full-run screen recording", e)
        } finally {
            fullRunRecording = null
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArtifactsGenerator::class.java)
    }
}
