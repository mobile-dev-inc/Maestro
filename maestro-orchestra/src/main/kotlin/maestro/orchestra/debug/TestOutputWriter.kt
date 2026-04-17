package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Pure write-path for debug artifacts produced during a flow run.
 *
 * The CLI's [maestro.cli.report.TestDebugReporter] is the long-standing
 * caller; the cloud worker ([dev.mobile.maestro.worker.MaestroTestRunner])
 * is the second caller. Both pass an already-populated [FlowDebugOutput]
 * and a target directory.
 */
object TestOutputWriter {

    private val logger = LoggerFactory.getLogger(TestOutputWriter::class.java)
    private val mapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        .writerWithDefaultPrettyPrinter()

    /**
     * Writes debug artifacts for one flow into [path].
     *
     * @param path destination directory (must exist).
     * @param flowName human-readable flow name; slashes are replaced with underscores in filenames.
     * @param debugOutput accumulated debug state for the flow.
     * @param filenamePrefix inserted into filenames right after the `commands-`/`screenshot-` stem.
     *                      Must be empty or end with `"-"` so the resulting filename is well-formed.
     *                      CLI passes `"shard-1-"` for shard index 0, `""` otherwise.
     * @param logPrefix prepended to error log messages from this writer. CLI passes `"[shard 1] "` etc.
     */
    fun saveFlow(
        path: Path,
        flowName: String,
        debugOutput: FlowDebugOutput,
        filenamePrefix: String = "",
        logPrefix: String = "",
    ) {
        // commands JSON
        try {
            val commandMetadata = debugOutput.commands
            if (commandMetadata.isNotEmpty()) {
                val commandsFilename = "commands-$filenamePrefix(${flowName.replace("/", "_")}).json"
                val file = File(path.absolutePathString(), commandsFilename)
                commandMetadata.map { CommandDebugWrapper(it.key, it.value) }.let {
                    mapper.writeValue(file, it)
                }
            }
        } catch (e: JsonMappingException) {
            logger.error("${logPrefix}Unable to parse commands", e)
        }

        // screenshots with status-tagged filenames
        debugOutput.screenshots.forEach {
            val status = when (it.status) {
                CommandStatus.COMPLETED -> "✅"
                CommandStatus.FAILED -> "❌"
                CommandStatus.WARNED -> "⚠️"
                else -> "﹖"
            }
            val filename = "screenshot-$filenamePrefix$status-${it.timestamp}-(${flowName}).png"
            val file = File(path.absolutePathString(), filename)
            it.screenshot.copyTo(file)
        }
    }

    private data class CommandDebugWrapper(
        val command: MaestroCommand,
        val metadata: CommandDebugMetadata,
    )
}
