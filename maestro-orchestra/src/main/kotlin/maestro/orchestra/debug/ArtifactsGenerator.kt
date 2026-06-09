package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.debuglog.ScopedLogCapture
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.ArtifactEntry
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactFiles
import maestro.orchestra.ArtifactManifest
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

    /** The run's artifact manifest; populated at [onFlowEnd], empty when [artifactsDir] is null. */
    var artifactManifest: ArtifactManifest = ArtifactManifest()
        private set
    private var logCapture: ScopedLogCapture? = null
    private var capturer: DeviceArtifactCapturer? = null
    private var flowStartMs: Long = 0L
    private var appUnderTest: String? = null

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            artifactsDir.toFile().mkdirs()
            logCapture = ScopedLogCapture.start(artifactsDir.resolve(ArtifactFiles.MAESTRO_LOG).toFile())
            flowStartMs = System.currentTimeMillis()
            capturer = DeviceArtifactCapturer(maestro, artifactsDir).also { it.start() }
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
        if (appUnderTest == null) cmd.launchAppCommand?.appId?.let { appUnderTest = it }
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
                    commandsFilename = ArtifactFiles.COMMANDS_JSON,
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

        if (artifactsDir != null) {
            val captured = capturer?.collect(appUnderTest, flowStartMs).orEmpty()
            capturer = null
            artifactManifest = buildManifest(artifactsDir, captured)
            try {
                artifactsDir.resolve(ArtifactFiles.MANIFEST_JSON).toFile()
                    .writeText(TestOutputWriter.bundleWriter.writeValueAsString(artifactManifest))
            } catch (e: Exception) {
                logger.warn("Failed to write manifest.json under $artifactsDir", e)
            }
        }
    }

    private fun buildManifest(dir: Path, captured: List<CapturedDeviceArtifact>): ArtifactManifest {
        val entries = buildList {
            dir.resolve(ArtifactFiles.COMMANDS_JSON).toFile().takeIf { it.exists() }?.let {
                add(ArtifactEntry(ArtifactKind.COMMAND_METADATA, ArtifactFormat.JSON, ArtifactFiles.COMMANDS_JSON, sizeBytes = it.length()))
            }
            dir.resolve(ArtifactFiles.MAESTRO_LOG).toFile().takeIf { it.exists() }?.let {
                add(ArtifactEntry(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, ArtifactFiles.MAESTRO_LOG, sizeBytes = it.length()))
            }
            dir.toFile()
                .listFiles { _, name -> name.startsWith(ArtifactFiles.FAILURE_SCREENSHOT_PREFIX) && name.endsWith(ArtifactFiles.SCREENSHOT_EXTENSION) }
                ?.sortedBy { it.name }
                ?.forEach { add(ArtifactEntry(ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, it.name, sizeBytes = it.length())) }
            captured.forEach { add(it.toEntry()) }
        }
        return ArtifactManifest(entries = entries)
    }

    private fun CapturedDeviceArtifact.toEntry(): ArtifactEntry {
        val kind = when (type) {
            CapturedDeviceArtifact.Type.DEVICE_LOG -> ArtifactKind.DEVICE_LOG
            CapturedDeviceArtifact.Type.CRASH_REPORT -> ArtifactKind.CRASH_REPORT
            CapturedDeviceArtifact.Type.ANR_REPORT -> ArtifactKind.ANR_REPORT
        }
        val entryMetadata = buildMap<String, String> {
            source?.let { put("source", it) }
            friendlyMessage?.let { put("message", it) }
        }
        return ArtifactEntry(
            kind = kind,
            format = ArtifactFormat.TXT,
            relativePath = file.name,
            sizeBytes = file.length(),
            metadata = entryMetadata,
        )
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
                "${ArtifactFiles.FAILURE_SCREENSHOT_PREFIX}${System.currentTimeMillis()}${ArtifactFiles.SCREENSHOT_EXTENSION}",
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
