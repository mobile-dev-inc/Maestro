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
import java.nio.file.Path

/**
 * Internal listener Orchestra always installs. Populates [FlowDebugOutput]
 * in memory (always on; consumers read it via `Orchestra.debugOutput`) and,
 * when [artifactsDir] is non-null, writes the per-flow artifact bundle
 * directly under it — see [BundleLayout] for the layout. With a null
 * [artifactsDir] (Studio's interactive runner) only the in-memory population
 * happens. The full artifact set — per-step screenshots, per-step view
 * hierarchy, and the full-run recording — is gated by [captureFullArtifacts]
 * (worker, not the CLI); off, only the failed step's screenshot and hierarchy
 * are captured.
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
    /** Highest sequence number that already captured a failure screenshot this flow; -1 if none. */
    private var lastFailureScreenshotSeq: Int = -1

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            val collector = ArtifactCollector(artifactsDir).also { this.collector = it }
            val logFile = collector.allocate(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, BundleLayout.MAESTRO_LOG)
            logCapture = ScopedLogCapture.start(logFile)
            flowStartMs = System.currentTimeMillis()
            appUnderTest = null
            lastFailureScreenshotSeq = -1
            capturer = DeviceArtifactCapturer(maestro, artifactsDir.resolve(BundleLayout.LOGS_DIR)).also { it.start() }
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
        // First launchApp wins (one flow tests one app); null ⇒ crash/ANR unscoped.
        if (appUnderTest == null) cmd.launchAppCommand?.appId?.let { appUnderTest = it }
    }

    /**
     * Allocate (and record) a command-output file through the collector, attributed
     * to the running command. Null when no bundle is produced ([artifactsDir] null) —
     * the caller then writes CWD-relative, as before.
     */
    fun allocateCommandArtifact(kind: ArtifactKind, fileName: String): File? {
        val collector = collector ?: return null
        return collector.allocateInCollection(kind, fileName, currentCommandMetadata?.command)
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

        // Hierarchy and screenshots are synchronous device round-trips. Failed and
        // warned steps always capture into the bundle (both run modes); passing
        // steps only when captureFullArtifacts is on (worker) — otherwise a local
        // run pays one viewHierarchy() round-trip per command (~100 for 100).
        // Failed uses the dedup-aware failure capture; the rest take a plain step shot.
        if (outcome is CommandOutcome.Failed || outcome is CommandOutcome.Warned || captureFullArtifacts) {
            captureStepHierarchy(metadata)
            if (outcome is CommandOutcome.Failed) captureFailureScreenshot(metadata)
            else captureStepScreenshot(metadata)
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

    override fun onAIArtifactGenerated(screenshot: Buffer, defectCount: Int) {
        val collector = collector ?: return
        val meta = currentCommandMetadata ?: return
        try {
            val destFile = collector.allocate(
                ArtifactKind.AI_ANALYSIS,
                ArtifactFormat.PNG,
                "${BundleLayout.AI_ANALYSIS_DIR}/step-${meta.sequenceNumber}${BundleLayout.SCREENSHOT_EXTENSION}",
                metadata = mapOf("defectCount" to defectCount.toString()),
                command = meta.command,
            )
            destFile.writeBytes(screenshot.copy().readByteArray())
        } catch (e: Exception) {
            logger.warn("Failed to capture AI analysis screenshot", e)
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
                "${BundleLayout.SCREEN_HIERARCHY_DIR}/step-${metadata.sequenceNumber}.json",
                command = metadata.command,
            )
            TestOutputWriter.bundleWriter.writeValue(destFile, tree)
        } catch (e: Exception) {
            logger.warn("Failed to capture step hierarchy", e)
        }
    }

    private fun captureFailureScreenshot(metadata: CommandDebugMetadata) {
        val collector = collector ?: return
        // A composite parent fails right after its leaf, on the same screen, with a
        // lower sequence number than the leaf that already captured. In the minimal
        // local bundle (flag off) skip that duplicate; a genuinely later failure
        // (continue-on-failure / optional) has a higher number and still gets its
        // own shot. With captureFullArtifacts on (worker) every step is recorded
        // individually, so the parent keeps its own screenshot too — no dedup.
        if (!captureFullArtifacts && metadata.sequenceNumber < lastFailureScreenshotSeq) return
        try {
            val destFile = collector.allocate(
                ArtifactKind.SCREENSHOT,
                ArtifactFormat.PNG,
                "${BundleLayout.STEP_SCREENSHOTS_DIR}/step-${metadata.sequenceNumber}${BundleLayout.SCREENSHOT_EXTENSION}",
                command = metadata.command,
            )
            val taken = ScreenshotUtils.takeDebugScreenshot(maestro = maestro, destFile = destFile)
            if (taken != null) lastFailureScreenshotSeq = metadata.sequenceNumber
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
                "${BundleLayout.STEP_SCREENSHOTS_DIR}/step-${metadata.sequenceNumber}${BundleLayout.SCREENSHOT_EXTENSION}",
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
