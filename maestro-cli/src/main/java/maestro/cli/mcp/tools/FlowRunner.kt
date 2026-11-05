package maestro.cli.mcp.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.Maestro
import maestro.MaestroException
import maestro.cli.mcp.viewer.McpViewerOrchestra
import maestro.cli.report.TestDebugReporter
import maestro.orchestra.ArtifactKind
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.util.Env.withEnv
import java.io.File
import java.nio.file.Path

/**
 * Runs one flow at a time for the MCP `run` tool, each into its own debug bundle
 * under [bundleRoot] — the same layout `maestro test` writes. The bundle holds the
 * full detail; [FlowRun.failure] is the compact summary that goes back inline.
 */
internal class FlowRunner(
    private val maestro: Maestro,
    val deviceId: String,
    private val bundleRoot: Path,
    private val captureAllSteps: Boolean,
) {

    fun run(flowName: String, commands: List<MaestroCommand>, env: Map<String, String>): FlowRun {
        // A fresh Orchestra per flow: its artifacts dir is fixed at construction.
        val flowDir = TestDebugReporter.createFlowDir(bundleRoot, flowName)
        val failures = FailureRecorder()
        val orchestra = McpViewerOrchestra.create(
            maestro = maestro,
            artifactsDir = flowDir,
            captureFullArtifacts = captureAllSteps,
            listeners = listOf(failures),
        )
        val error = try {
            val result = runBlocking { orchestra.runFlow(commands.withEnv(env)) }
            if (result.success) null else failures.error ?: IllegalStateException("Flow failed")
        } catch (e: Exception) {
            e
        }
        return FlowRun(
            artifactsDir = flowDir,
            commandCount = commands.size,
            failure = error?.let { summarize(it, failures, flowDir) },
        )
    }

    private fun summarize(error: Throwable, failures: FailureRecorder, flowDir: Path) = FlowFailure(
        error = error.message ?: error::class.simpleName ?: "Unknown error",
        failedCommand = failures.command?.let(::yamlOf),
        // The error that ended the flow may wrap the step's own (e.g. AppCrash suppresses it).
        debugMessage = debugMessageOf(error)
            ?: error.suppressed.firstNotNullOfOrNull(::debugMessageOf)
            ?: failures.error?.let(::debugMessageOf),
        screenshot = failedStepScreenshot(flowDir),
    )

    private fun debugMessageOf(error: Throwable): String? = when (error) {
        is MaestroException.AssertionFailure -> error.debugMessage
        is MaestroException.HideKeyboardFailure -> error.debugMessage
        is MaestroException.DriverTimeout -> error.debugMessage
        else -> null
    }

    private fun yamlOf(command: MaestroCommand): String {
        val yaml = command.sourceInfo
            ?.let { it.source.substring(it.startOffset, it.endOffset).trim() }
            ?: command.description()
        return if (yaml.length <= MAX_COMMAND_CHARS) yaml else yaml.take(MAX_COMMAND_CHARS) + "…"
    }

    /** Remembers the innermost failed command: the last leaf to fail, else the last command of any kind. */
    private class FailureRecorder : OrchestraListener {
        var command: MaestroCommand? = null
            private set
        var error: Throwable? = null
            private set
        private var recordedLeaf = false

        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
            // A later step is running, so an earlier failure was recovered from (retry, optional).
            recordedLeaf = false
        }

        override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) {
            if (outcome !is CommandOutcome.Failed) return
            val isLeaf = cmd.asCommand() !is CompositeCommand
            // Composites fail after their failed child; keep the child.
            if (!isLeaf && recordedLeaf) return
            command = cmd
            error = outcome.error
            recordedLeaf = isLeaf
        }
    }

    internal companion object {
        private const val MAX_COMMAND_CHARS = 300
        private const val COMMANDS_JSON = "commands.json"

        /**
         * The failed step's screenshot, from commands.json. Composites that fail with their
         * child record a screenshot too, but only leaves record a view hierarchy alongside it,
         * so the leaf's shot is preferred.
         */
        internal fun failedStepScreenshot(flowDir: Path): File? = try {
            val commandsFile = flowDir.resolve(COMMANDS_JSON).toFile()
            if (!commandsFile.exists()) {
                null
            } else {
                val failedArtifacts = Json.parseToJsonElement(commandsFile.readText()).jsonArray
                    .map { it.jsonObject["metadata"]?.jsonObject ?: JsonObject(emptyMap()) }
                    .filter { it["status"]?.jsonPrimitive?.contentOrNull == CommandStatus.FAILED.name }
                    .map { metadata ->
                        metadata["artifacts"]?.jsonArray.orEmpty().associate { artifact ->
                            val fields = artifact.jsonObject
                            fields["type"]?.jsonPrimitive?.contentOrNull to fields["path"]?.jsonPrimitive?.contentOrNull
                        }
                    }
                val chosen = failedArtifacts.lastOrNull { ArtifactKind.SCREEN_HIERARCHY.name in it }
                    ?: failedArtifacts.lastOrNull { ArtifactKind.SCREENSHOT.name in it }
                chosen?.get(ArtifactKind.SCREENSHOT.name)
                    ?.let { flowDir.resolve(it).toFile() }
                    ?.takeIf { it.exists() }
            }
        } catch (e: Exception) {
            null
        }
    }
}

internal data class FlowRun(
    val artifactsDir: Path,
    val commandCount: Int,
    /** Null when the flow passed. */
    val failure: FlowFailure?,
) {
    val success: Boolean get() = failure == null
}

internal data class FlowFailure(
    val error: String,
    val failedCommand: String?,
    val debugMessage: String?,
    val screenshot: File?,
)
