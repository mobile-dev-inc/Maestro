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
 * Split into two narrow operations so callers (CLI's
 * [maestro.cli.report.TestDebugReporter] and the cloud worker's
 * MaestroTestRunner) compose their own filenames without having to
 * thread prefix/suffix knobs through the API.
 *
 * - [saveCommands] writes the single `commands-*.json` metadata file.
 * - [saveScreenshots] copies caller-named screenshot files into the
 *   destination path.
 * - [emojiFor] exposes the status→emoji mapping so both callers can
 *   produce the same tagged filenames.
 */
object TestOutputWriter {

    private val logger = LoggerFactory.getLogger(TestOutputWriter::class.java)
    private val mapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        .writerWithDefaultPrettyPrinter()

    /**
     * Writes the commands JSON into [path] under [commandsFilename]. If
     * [FlowDebugOutput.commands] is empty, no file is written.
     *
     * @param path destination directory (must exist).
     * @param debugOutput accumulated debug state for the flow.
     * @param commandsFilename full filename (e.g. `"commands-(my_flow).json"` or
     *   `"commands.json"`). Caller owns this string completely.
     * @param logPrefix prepended to error log messages from this writer.
     */
    fun saveCommands(
        path: Path,
        debugOutput: FlowDebugOutput,
        commandsFilename: String,
        logPrefix: String = "",
    ) {
        try {
            val commandMetadata = debugOutput.commands
            if (commandMetadata.isNotEmpty()) {
                val file = File(path.absolutePathString(), commandsFilename)
                commandMetadata.map { CommandDebugWrapper(it.key, it.value) }.let {
                    mapper.writeValue(file, it)
                }
            }
        } catch (e: JsonMappingException) {
            logger.error("${logPrefix}Unable to parse commands", e)
        }
    }

    /**
     * Copies each [NamedScreenshot] into [path] using the caller-supplied
     * filename.
     */
    fun saveScreenshots(path: Path, namedScreenshots: List<NamedScreenshot>) {
        namedScreenshots.forEach { it.source.copyTo(File(path.absolutePathString(), it.filename)) }
    }

    /**
     * Status→emoji mapping used by CLI and cloud to produce matching
     * tagged screenshot filenames.
     */
    fun emojiFor(status: CommandStatus): String = when (status) {
        CommandStatus.COMPLETED -> "✅"
        CommandStatus.FAILED -> "❌"
        CommandStatus.WARNED -> "⚠\uFE0F"
        else -> "﹖"
    }

    data class NamedScreenshot(val source: File, val filename: String)

    private data class CommandDebugWrapper(
        val command: MaestroCommand,
        val metadata: CommandDebugMetadata,
    )
}
