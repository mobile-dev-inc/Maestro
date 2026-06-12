package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonIncludeProperties
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.ArtifactFiles
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Pure write-path for the flow-debug bundle files. Callers (ArtifactsGenerator,
 * the CLI's TestDebugReporter, the cloud worker) compose their own filenames, so
 * no prefix/suffix knobs are threaded through the API.
 */
object TestOutputWriter {

    private val logger = LoggerFactory.getLogger(TestOutputWriter::class.java)

    /** Errors serialize as message + debugMessage only — hierarchy lives in screen-hierarchy/, stack traces in maestro.log. */
    @JsonIncludeProperties("message", "debugMessage")
    private abstract class SlimThrowableMixin

    /** Shared mapper for all bundle files: omits nulls/empties (NON_EMPTY). */
    private val bundleMapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        .addMixIn(Throwable::class.java, SlimThrowableMixin::class.java)

    internal val bundleWriter = bundleMapper.writerWithDefaultPrettyPrinter()

    private val mapper = bundleWriter

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
     * Writes [manifest] to [path]/manifest.json with a leading `$schema`
     * ([ArtifactFiles.MANIFEST_SCHEMA_URL]) so it stays self-describing wherever it ends up.
     */
    fun saveManifest(path: Path, manifest: ArtifactManifest) {
        val tree = bundleMapper.valueToTree<ObjectNode>(manifest)
        val withSchema = bundleMapper.createObjectNode()
            .put("\$schema", ArtifactFiles.MANIFEST_SCHEMA_URL)
            .setAll<ObjectNode>(tree)
        File(path.absolutePathString(), ArtifactFiles.MANIFEST_JSON)
            .writeText(bundleWriter.writeValueAsString(withSchema))
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
