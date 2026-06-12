package maestro.orchestra.debug

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.debuglog.ScopedLogCapture
import maestro.orchestra.ArtifactEntry
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactFiles
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import okio.sink
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
 *   - When `artifactsDir` is non-null: produces the on-disk flow-debug bundle
 *     directly under `artifactsDir` (the run root, which is itself the zippable
 *     bundle — no intermediate `artifacts/` folder):
 *       `manifest.json` — self-describing index of everything below
 *       `commands.json` — per-command metadata, hierarchy inline on the failing
 *         command
 *       `logs/maestro.log` — scoped capture of `maestro.*` loggers
 *       `screenshot-❌-<unix-millis>.png` — auto-capture at the moment of a
 *         command failure
 *
 *   - When the corresponding flag is on (worker, not the CLI):
 *       `screenshots/step-<sequenceNumber>.png` — a screenshot after each
 *         non-failed command ([captureStepScreenshots])
 *       `screen-recording.mp4` — a recording of the whole run
 *         ([captureScreenRecording])
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
    private val captureStepScreenshots: Boolean = false,
    private val captureScreenRecording: Boolean = false,
) : OrchestraListener {

    val debugOutput = FlowDebugOutput()

    /** The run's artifact manifest; populated at [onFlowEnd], empty when [artifactsDir] is null. */
    var artifactManifest: ArtifactManifest = ArtifactManifest()
        private set
    private var logCapture: ScopedLogCapture? = null
    private var fullRunRecording: ScreenRecording? = null
    private var currentCommandMetadata: CommandDebugMetadata? = null

    override fun onFlowStart() {
        if (artifactsDir == null) return
        try {
            // Creates the run root and logs/ in one shot.
            artifactsDir.resolve(ArtifactFiles.LOGS_DIR).toFile().mkdirs()
            logCapture = ScopedLogCapture.start(artifactsDir.resolve(ArtifactFiles.MAESTRO_LOG).toFile())
        } catch (e: Exception) {
            logger.warn("Failed to set up artifacts directory at $artifactsDir", e)
        }
        if (captureScreenRecording) startFullRunRecording()
    }

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) {
        debugOutput.commands[cmd] = CommandDebugMetadata(
            timestamp = System.currentTimeMillis(),
            status = CommandStatus.RUNNING,
            sequenceNumber = sequenceNumber,
        ).also { currentCommandMetadata = it }
    }

    override fun onCommandArtifact(relativePath: String) {
        if (artifactsDir == null) return
        currentCommandMetadata?.artifacts?.add(relativePath)
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
            // Expensive device round-trips; only when producing a bundle. Each is
            // independent best-effort — one failing doesn't block the other.
            if (artifactsDir != null) {
                captureHierarchy(metadata)
                captureFailureScreenshot()
            }
        } else if (artifactsDir != null && captureStepScreenshots &&
            (outcome is CommandOutcome.Completed || outcome is CommandOutcome.Warned)
        ) {
            // The failure screenshot already covers failed commands, and skipped
            // commands never ran, so only commands that actually executed get a
            // per-step screenshot.
            captureStepScreenshot(metadata.sequenceNumber)
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
            artifactManifest = buildManifest(artifactsDir)
            try {
                TestOutputWriter.saveManifest(artifactsDir, artifactManifest)
            } catch (e: Exception) {
                logger.warn("Failed to write manifest.json under $artifactsDir", e)
            }
        }
    }

    private fun buildManifest(dir: Path): ArtifactManifest {
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
                ?.forEach {
                    add(ArtifactEntry(
                        ArtifactKind.SCREENSHOT,
                        ArtifactFormat.PNG,
                        it.name,
                        sizeBytes = it.length(),
                        metadata = mapOf("source" to "failure"),
                    ))
                }
            addFolderEntry(dir, ArtifactFiles.TAKE_SCREENSHOT_DIR, ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, source = "take_screenshot")
            addFolderEntry(dir, ArtifactFiles.START_RECORDING_DIR, ArtifactKind.SCREEN_RECORDING, ArtifactFormat.MP4, source = "start_recording")
            addFolderEntry(dir, ArtifactFiles.STEP_SCREENSHOTS_DIR, ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, source = "step")
            dir.resolve(ArtifactFiles.SCREEN_RECORDING).toFile().takeIf { it.isFile }?.let {
                add(ArtifactEntry(
                    ArtifactKind.SCREEN_RECORDING,
                    ArtifactFormat.MP4,
                    ArtifactFiles.SCREEN_RECORDING,
                    sizeBytes = it.length(),
                    metadata = mapOf("source" to "full_run"),
                ))
            }
        }
        return ArtifactManifest(entries = entries)
    }

    private fun MutableList<ArtifactEntry>.addFolderEntry(
        dir: Path,
        subdir: String,
        kind: ArtifactKind,
        format: ArtifactFormat,
        source: String,
    ) {
        val folder = dir.resolve(subdir).toFile().takeIf { it.isDirectory } ?: return
        val count = folder.walkTopDown().count { it.isFile }
        if (count > 0) add(ArtifactEntry(kind, format, subdir, count = count, metadata = mapOf("source" to source)))
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

    private fun captureStepScreenshot(sequenceNumber: Int) {
        if (artifactsDir == null) return
        try {
            val dir = artifactsDir.resolve(ArtifactFiles.STEP_SCREENSHOTS_DIR).toFile()
            dir.mkdirs()
            val destFile = File(dir, "step-$sequenceNumber${ArtifactFiles.SCREENSHOT_EXTENSION}")
            runBlocking { maestro.takeScreenshot(destFile.sink(), false) }
        } catch (e: Exception) {
            logger.warn("Failed to capture per-step screenshot", e)
        }
    }

    private fun startFullRunRecording() {
        if (artifactsDir == null) return
        try {
            val destFile = artifactsDir.resolve(ArtifactFiles.SCREEN_RECORDING).toFile()
            destFile.parentFile?.mkdirs()
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
