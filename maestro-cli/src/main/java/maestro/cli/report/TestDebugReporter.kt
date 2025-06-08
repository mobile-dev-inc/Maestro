package maestro.cli.report

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.MaestroException
import maestro.TreeNode
import maestro.ai.cloud.Defect
import maestro.cli.runner.CommandStatus
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils
import maestro.debuglog.DebugLogStore
import maestro.debuglog.LogConfig
import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists


// TODO(bartekpacia): Rename to TestOutputReporter, because it's not only for "debug" stuff
object TestDebugReporter {

    private val logger = LoggerFactory.getLogger(TestDebugReporter::class.java)
    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY).writerWithDefaultPrettyPrinter()

    private var debugOutputPath: Path? = null
    private var debugOutputPathAsString: String? = null
    private var flattenDebugOutput: Boolean = false

    // AI outputs must be saved separately at the end of the flow.
    fun saveSuggestions(outputs: List<FlowAIOutput>, path: Path) {
        // This mutates the output.
        outputs.forEach { output ->
            // Write AI screenshots. Paths need to be changed to the final ones.
            val updatedOutputs = output.screenOutputs.map { newOutput ->
                val screenshotFilename = newOutput.screenshotPath.name
                val screenshotFile = File(path.absolutePathString(), screenshotFilename)
                newOutput.screenshotPath.copyTo(screenshotFile)
                newOutput.copy(screenshotPath = screenshotFile)
            }

            output.screenOutputs.clear()
            output.screenOutputs.addAll(updatedOutputs)

            // Write AI JSON output
            val jsonFilename = "ai-(${output.flowName.replace("/", "_")}).json"
            val jsonFile = File(path.absolutePathString(), jsonFilename)
            mapper.writeValue(jsonFile, output)
        }

        HtmlAITestSuiteReporter().report(outputs, path.toFile())
    }

    /**
     * Save debug information about a single flow, after it has finished.
     */
    fun saveFlow(flowName: String, debugOutput: FlowDebugOutput, path: Path, shardIndex: Int? = null) {
        // TODO(bartekpacia): Potentially accept a single "FlowPersistentOutput" object
        // TODO(bartekpacia: Build output incrementally, instead of single-shot on flow completion
        //  Be aware that this goal somewhat conflicts with including links to other flows in the HTML report.

        val shardPrefix = shardIndex?.let { "shard-${it + 1}-" }.orEmpty()
        val shardLogPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

        // commands
        try {
            val commandMetadata = debugOutput.commands
            if (commandMetadata.isNotEmpty()) {
                val commandsFilename = "commands-$shardPrefix(${flowName.replace("/", "_")}).json"
                val file = File(path.absolutePathString(), commandsFilename)
                commandMetadata.map {
                    CommandDebugWrapper(it.key, it.value)
                }.let {
                    mapper.writeValue(file, it)
                }
            }
        } catch (e: JsonMappingException) {
            logger.error("${shardLogPrefix}Unable to parse commands", e)
        }

        // screenshots
        debugOutput.screenshots.forEach {
            val status = when (it.status) {
                CommandStatus.COMPLETED -> "✅"
                CommandStatus.FAILED -> "❌"
                CommandStatus.WARNED -> "⚠️"
                else -> "﹖"
            }
            val filename = "screenshot-$shardPrefix$status-${it.timestamp}-(${flowName}).png"
            val file = File(path.absolutePathString(), filename)

            it.screenshot.copyTo(file)
        }
    }

    fun deleteOldFiles(days: Long = 14) {
        try {
            val currentTime = Instant.now()
            val daysLimit = currentTime.minus(Duration.of(days, ChronoUnit.DAYS))

            Files.walk(getDebugOutputPath()).filter {
                val fileTime = Files.getAttribute(it, "basic:lastModifiedTime") as FileTime
                val isOlderThanLimit = fileTime.toInstant().isBefore(daysLimit)
                Files.isDirectory(it) && isOlderThanLimit
            }.sorted(Comparator.reverseOrder()).forEach { dir ->
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { file -> Files.delete(file) }
            }
        } catch (e: Exception) {
            logger.warn("Failed to delete older files", e)
        }
    }

    private fun logSystemInfo() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = loggerContext.getLogger("io.netty")
        val grpcLogger = loggerContext.getLogger("io.grpc")
        rootLogger.setLevel(Level.OFF)
        grpcLogger.setLevel(Level.OFF)

        val logger = LoggerFactory.getLogger("MAESTRO")
        logger.info("---- System Info ----")
        logger.info("Maestro Version: ${EnvUtils.CLI_VERSION ?: "Undefined"}")
        logger.info("CI: ${CiUtils.getCiProvider() ?: "Undefined"}")
        logger.info("OS Name: ${EnvUtils.OS_NAME}")
        logger.info("OS Version: ${EnvUtils.OS_VERSION}")
        logger.info("Architecture: ${EnvUtils.OS_ARCH}")
        logger.info("Java Version: ${EnvUtils.getJavaVersion()}")
        logger.info("Xcode Version: ${IOSEnvUtils.xcodeVersion}")
        logger.info("Flutter Version: ${EnvUtils.getFlutterVersionAndChannel().first ?: "Undefined"}")
        logger.info("Flutter Channel: ${EnvUtils.getFlutterVersionAndChannel().second ?: "Undefined"}")
        logger.info("---------------------")
    }

    /**
     * Calls to this method should be done as soon as possible, to make all
     * loggers use our custom configuration rather than the defaults.
     */
    fun install(debugOutputPathAsString: String? = null, flattenDebugOutput: Boolean = false, printToConsole: Boolean) {
        this.debugOutputPathAsString = debugOutputPathAsString
        this.flattenDebugOutput = flattenDebugOutput
        val path = getDebugOutputPath()
        LogConfig.configure(logFileName = path.absolutePathString() + "/maestro.log", printToConsole = printToConsole)
        logSystemInfo()
        DebugLogStore.logSystemInfo()
    }

    fun getDebugOutputPath(): Path {
        if (debugOutputPath != null) return debugOutputPath as Path

        val debugRootPath =
            if (debugOutputPathAsString != null) debugOutputPathAsString!! else System.getProperty("user.home")
        val debugOutput =
            if (flattenDebugOutput) Paths.get(debugRootPath) else buildDefaultDebugOutputPath(debugRootPath)

        if (!debugOutput.exists()) {
            Files.createDirectories(debugOutput)
        }
        debugOutputPath = debugOutput
        return debugOutput
    }

    private fun buildDefaultDebugOutputPath(debugRootPath: String): Path {
        val preamble = arrayOf(".maestro", "tests")
        val foldername = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").format(LocalDateTime.now())
        return Paths.get(debugRootPath, *preamble, foldername)
    }
}

private data class CommandDebugWrapper(
    val command: MaestroCommand, val metadata: CommandDebugMetadata
)

data class CommandDebugMetadata(
    var status: CommandStatus? = null,
    var timestamp: Long? = null,
    var duration: Long? = null,
    var error: Throwable? = null,
    var hierarchy: TreeNode? = null
) {
    fun calculateDuration() {
        if (timestamp != null) {
            duration = System.currentTimeMillis() - timestamp!!
        }
    }
}

data class FlowDebugOutput(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    val screenshots: MutableList<Screenshot> = mutableListOf(),
    var exception: MaestroException? = null,
) {
    data class Screenshot(
        val screenshot: File,
        val timestamp: Long,
        val status: CommandStatus,
    )
}

data class FlowAIOutput(
    @JsonProperty("flow_name") val flowName: String,
    @JsonProperty("flow_file_path") val flowFile: File,
    @JsonProperty("outputs") val screenOutputs: MutableList<SingleScreenFlowAIOutput> = mutableListOf(),
)

data class SingleScreenFlowAIOutput(
    @JsonProperty("screenshot_path") val screenshotPath: File,
    val defects: List<Defect>,
)
