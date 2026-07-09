package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.debuglog.ScopedLogCapture
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import okio.Buffer
import okio.sink
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Internal listener Orchestra always installs. Populates [FlowDebugOutput]
 * in memory (always on; consumers read it via `Orchestra.debugOutput`) and,
 * when [artifactsDir] is non-null, writes the per-flow artifact bundle
 * directly under it — see [BundleLayout] for the layout. With a null
 * [artifactsDir] (Studio's interactive runner) only the in-memory population
 * happens. Under [captureFullArtifacts] (worker, not the CLI) every step gets a
 * pre-command screenshot plus a full-run recording; failed/warned steps overwrite
 * theirs with an at-outcome frame paired with a view hierarchy (so the two match).
 * With the flag off, only failed/warned steps capture that pair. The ~1s hierarchy
 * round-trip is why only they ever pay for it.
 *
 * Each per-step shot is the screen *before* that step, so step N+1's is also step N's
 * end state; a single flow-end shot (`screenshots/final.png`, flow-level) closes the
 * chain with the screen the run ended on — after any onFlowComplete teardown — giving
 * complete start-and-end evidence.
 *
 * Every file is routed through an [ArtifactCollector]: the manifest is the
 * collector's records and each command's artifact list is the same records
 * grouped by command, so the two can never disagree. Device logs + crash/ANR
 * are captured by [DeviceArtifactCapturer] and adopted into that same collector.
 */
internal class ArtifactsGenerator(
    private val artifactsDir: Path?,
    private val maestro: Maestro,
    private val captureFullArtifacts: Boolean = false,
    private val onStepScreenshotCaptured: (sequenceNumber: Int, relativePath: String) -> Unit = { _, _ -> },
) : OrchestraListener {

    val debugOutput = FlowDebugOutput()

    /** The run's artifact manifest; populated at [onFlowEnd], empty when [artifactsDir] is null. */
    var artifactManifest: ArtifactManifest = ArtifactManifest()
        private set
    private var collector: ArtifactCollector? = null
    private var logCapture: ScopedLogCapture? = null
    private var fullRunRecording: ScreenRecording? = null
    private var capturer: DeviceArtifactCapturer? = null
    private var flowStartMs: Long = 0L
    private var appUnderTest: String? = null
    /**
     * Artifacts are emitted synchronously by the currently-executing leaf
     * command, so a single reference (no stack) is enough to attribute them.
     */
    private var currentCommandMetadata: CommandDebugMetadata? = null

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            val collector = ArtifactCollector(artifactsDir).also { this.collector = it }
            val logFile = collector.allocate(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, BundleLayout.MAESTRO_LOG)
            logCapture = ScopedLogCapture.start(logFile)
            flowStartMs = System.currentTimeMillis()
            appUnderTest = null
            capturer = DeviceArtifactCapturer(maestro, artifactsDir.resolve(BundleLayout.LOGS_DIR)).also { it.start() }
        } catch (e: Exception) {
            logger.warn("Failed to set up artifacts directory at $artifactsDir", e)
        }
        if (captureFullArtifacts) startFullRunRecording()
    }

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
        val metadata = CommandDebugMetadata(
            timestamp = System.currentTimeMillis(),
            status = CommandStatus.RUNNING,
            sequenceNumber = sequenceNumber,
            depth = depth,
            command = cmd,
        )
        // Same object in both: identity map for live attribution, executedSteps for
        // the per-execution record (one entry per attempt).
        debugOutput.commands[cmd] = metadata
        debugOutput.executedSteps.add(metadata)
        currentCommandMetadata = metadata
        // First launchApp wins (one flow tests one app); null ⇒ crash/ANR unscoped.
        if (appUnderTest == null) cmd.launchAppCommand?.appId?.let { appUnderTest = it }

        // Pre-command shot: the screen the step is about to act on.
        if (captureFullArtifacts && !StepArtifactNaming.isNoOp(cmd)) captureStepScreenshot(metadata)
    }

    /**
     * Allocate (and record) a command-output file through the collector, attributed
     * to the running command. Null when no bundle is produced ([artifactsDir] null) —
     * the caller then writes CWD-relative, as before.
     */
    fun allocateCommandArtifact(kind: ArtifactKind, fileName: String): File? {
        val collector = collector ?: return null
        return collector.allocateInCollection(
            kind, fileName, currentCommandMetadata?.sequenceNumber,
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
        }
        if (artifactsDir == null || outcome is CommandOutcome.Skipped) return
        // Passing steps keep their pre-command shot from onCommandStart; nothing to do at finish.
        if (outcome !is CommandOutcome.Failed && outcome !is CommandOutcome.Warned) return
        // A failing composite's own failing leaf still records the pair, so skipping no-ops loses nothing.
        if (StepArtifactNaming.isNoOp(cmd)) return

        // Failed/warned leaves pair screenshot + hierarchy at the outcome moment so both show the
        // same screen (the viewer overlays them). viewHierarchy() is ~1s, so only these steps pay it.
        captureStepHierarchy(metadata)
        if (captureFullArtifacts) {
            // Overwrite the pre-command shot with the at-outcome frame; its callback already fired.
            captureStepScreenshotFile(metadata)
        } else {
            captureStepScreenshot(metadata)
        }
    }

    override fun onCommandReset(cmd: MaestroCommand) {
        // No-op: a reset precedes the next execution's own entry. Mutating the current
        // one would downgrade the execution that just finished (shared via the map).
    }

    override fun onCommandMetadataUpdate(cmd: MaestroCommand, metadata: Orchestra.CommandMetadata) {
        debugOutput.commands[cmd]?.let { existing ->
            existing.evaluatedCommand = metadata.evaluatedCommand
        }
    }

    override fun onAIArtifactGenerated(screenshot: Buffer, defectCount: Int) {
        val collector = collector ?: return
        val meta = currentCommandMetadata ?: return
        try {
            val destFile = collector.allocate(
                ArtifactKind.AI_ANALYSIS,
                ArtifactFormat.PNG,
                "${BundleLayout.AI_ANALYSIS_DIR}/${StepArtifactNaming.stem(meta.sequenceNumber, meta.command)}${BundleLayout.SCREENSHOT_EXTENSION}",
                metadata = mapOf("defectCount" to defectCount.toString()),
                sequenceNumber = meta.sequenceNumber,
            )
            destFile.writeBytes(screenshot.copy().readByteArray())
        } catch (e: Exception) {
            logger.warn("Failed to capture AI analysis screenshot", e)
        }
    }

    override fun onFlowEnd() {
        // Capture the resting screen before the recording is torn down.
        if (captureFullArtifacts) captureFinalScreenshot()
        stopFullRunRecording()
        val collector = collector
        if (artifactsDir != null && collector != null) {
            // Per execution, keyed by sequence number — the same records the manifest
            // reads, so commands.json can't drift and each attempt keeps its own shot.
            debugOutput.executedSteps.forEach { step ->
                step.artifacts.clear()
                step.artifacts.addAll(collector.artifactsForStep(step.sequenceNumber))
            }
            try {
                TestOutputWriter.saveCommands(
                    path = artifactsDir,
                    debugOutput = debugOutput,
                    commandsFilename = BundleLayout.COMMANDS_JSON,
                )
                collector.adopt(ArtifactKind.COMMAND_METADATA, BundleLayout.COMMANDS_JSON, ArtifactFormat.JSON)
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
            // Device logs + crash/ANR land under logs/ and are adopted flow-level
            // (no owning command) so they appear in the manifest like any artifact.
            capturer?.collect(appUnderTest, flowStartMs).orEmpty()
                .forEach { collector.adoptDeviceArtifact(it) }
            capturer = null
            artifactManifest = collector.manifest()
            try {
                TestOutputWriter.saveManifest(artifactsDir, artifactManifest)
            } catch (e: Exception) {
                logger.warn("Failed to write manifest.json under $artifactsDir", e)
            }
        }
    }

    private fun ArtifactCollector.adoptDeviceArtifact(captured: CapturedDeviceArtifact) {
        val kind = when (captured.type) {
            CapturedDeviceArtifact.Type.DEVICE_LOG -> ArtifactKind.DEVICE_LOG
            CapturedDeviceArtifact.Type.CRASH_REPORT -> ArtifactKind.CRASH_REPORT
            CapturedDeviceArtifact.Type.ANR_REPORT -> ArtifactKind.ANR_REPORT
        }
        val metadata = buildMap {
            captured.source?.let { put("source", it) }
            captured.friendlyMessage?.let { put("message", it) }
        }
        // Capturer writes into logs/; path stays run-root-relative.
        adopt(kind, "${BundleLayout.LOGS_DIR}/${captured.file.name}", ArtifactFormat.TXT, metadata)
    }

    private fun captureStepHierarchy(metadata: CommandDebugMetadata) {
        val collector = collector ?: return
        try {
            val tree = runBlocking { maestro.viewHierarchy() }.root
            val destFile = collector.allocate(
                ArtifactKind.SCREEN_HIERARCHY,
                ArtifactFormat.JSON,
                "${BundleLayout.SCREEN_HIERARCHY_DIR}/${StepArtifactNaming.stem(metadata.sequenceNumber, metadata.command)}.json",
                sequenceNumber = metadata.sequenceNumber,
            )
            TestOutputWriter.bundleWriter.writeValue(destFile, tree)
        } catch (e: Exception) {
            logger.warn("Failed to capture step hierarchy", e)
        }
    }

    private fun captureStepScreenshot(metadata: CommandDebugMetadata) {
        val relativePath = captureStepScreenshotFile(metadata) ?: return
        onStepScreenshotCaptured(metadata.sequenceNumber, relativePath)
    }

    private fun captureStepScreenshotFile(metadata: CommandDebugMetadata): String? =
        captureScreenshot(
            "${BundleLayout.STEP_SCREENSHOTS_DIR}/${StepArtifactNaming.stem(metadata.sequenceNumber, metadata.command)}${BundleLayout.SCREENSHOT_EXTENSION}",
            sequenceNumber = metadata.sequenceNumber,
        )

    /** Flow-end shot of the resting screen — flow-level (owns no step). */
    private fun captureFinalScreenshot() {
        captureScreenshot(BundleLayout.FINAL_SCREENSHOT, sequenceNumber = null)
    }

    /**
     * Captures [relativePath] (via a sibling temp moved into place on success, so a failed recapture
     * never destroys a frame already there), returning the bundle-relative path or null on failure.
     */
    private fun captureScreenshot(relativePath: String, sequenceNumber: Int?): String? {
        val collector = collector ?: return null
        val destFile = collector.allocate(
            ArtifactKind.SCREENSHOT,
            ArtifactFormat.PNG,
            relativePath,
            sequenceNumber = sequenceNumber,
        )
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        return try {
            if (ScreenshotUtils.takeDebugScreenshot(maestro = maestro, destFile = tempFile) == null) {
                logger.warn("Failed to capture screenshot $relativePath")
                tempFile.delete()
                return null
            }
            Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            relativePath
        } catch (e: Exception) {
            logger.warn("Failed to capture screenshot $relativePath", e)
            tempFile.delete()
            null
        }
    }

    private fun startFullRunRecording() {
        val collector = collector ?: return
        try {
            val destFile = collector.allocate(ArtifactKind.SCREEN_RECORDING, ArtifactFormat.MP4, BundleLayout.SCREEN_RECORDING)
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
