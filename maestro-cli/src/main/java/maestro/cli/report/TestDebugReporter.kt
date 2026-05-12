package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.ai.cloud.Defect
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.TestOutputWriter
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils
import maestro.debuglog.DebugLogStore
import maestro.debuglog.LogConfig
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator
import org.slf4j.Logger
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
import kotlin.math.log


// TODO(bartekpacia): Rename to TestOutputReporter, because it's not only for "debug" stuff
object TestDebugReporter {

    private val logger = LogManager.getLogger(TestDebugReporter::class.java)
    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY).writerWithDefaultPrettyPrinter()
    private val folderNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    private var debugOutputPath: Path? = null
    private var debugOutputPathAsString: String? = null
    private var flattenDebugOutput: Boolean = false
    private var testOutputDir: Path? = null
    private var sessionFolderName: String? = null

    // AI outputs must be saved separately at the end of the flow.
    fun saveSuggestions(outputs: List<FlowAIOutput>, path: Path) {
        val outputsWithContent = outputs.filter { it.screenOutputs.isNotEmpty() }
        if (outputsWithContent.isEmpty()) return

        // This mutates the output.
        outputsWithContent.forEach { output ->
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

        HtmlAITestSuiteReporter().report(outputsWithContent, path.toFile())
    }

    /**
     * Save debug information about a single flow, after it has finished.
     * Delegates to [maestro.orchestra.debug.TestOutputWriter] so CLI and cloud
     * share the same on-disk output format. Used by [TestRunner.runSingle]
     * (one-shot and continuous modes) which has not been migrated to the
     * listener-based artifact production yet.
     */
    fun saveFlow(flowName: String, debugOutput: FlowDebugOutput, path: Path, shardIndex: Int? = null) {
        val shardPrefix = shardIndex?.let { "shard-${it + 1}-" }.orEmpty()
        val logPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()
        val cleanFlow = flowName.replace("/", "_")

        TestOutputWriter.saveCommands(
            path = path,
            debugOutput = debugOutput,
            commandsFilename = "commands-$shardPrefix($cleanFlow).json",
            logPrefix = logPrefix,
        )

        val named = debugOutput.screenshots.map { shot ->
            val emoji = TestOutputWriter.emojiFor(shot.status)
            TestOutputWriter.NamedScreenshot(
                source = shot.screenshot,
                filename = "screenshot-$shardPrefix$emoji-${shot.timestamp}-($cleanFlow).png",
            )
        }
        TestOutputWriter.saveScreenshots(path, named)
    }

    /**
     * Renames the canonical flow-debug bundle (produced by Maestro's
     * `ArtifactsGenerator` under [sourceDir]) into [destDir] using CLI's
     * historic flat naming scheme:
     *
     *   sourceDir/commands.json
     *     -> destDir/commands-[shard-N-]?(flow_name).json
     *   sourceDir/screenshot-<emoji>-<ts>.png
     *     -> destDir/screenshot-[shard-N-]?<emoji>-<ts>-(flow_name).png
     *
     * `maestro.log` produced by the scoped capture stays inside [sourceDir]
     * — CLI users already get a session-level log from
     * `TestDebugReporter.install` → `LogConfig.configure`, so we do not
     * surface a per-flow log file in the session dir.
     *
     * Used by [TestSuiteInteractor.runFlow] which produces its bundle via
     * Orchestra's `artifactsDir` param.
     */
    fun copyToFlatLayout(sourceDir: Path, destDir: Path, flowName: String, shardIndex: Int? = null) {
        val shardPrefix = shardIndex?.let { "shard-${it + 1}-" }.orEmpty()
        val cleanFlow = flowName.replace("/", "_")
        val src = sourceDir.toFile()
        val dst = destDir.toFile().also { it.mkdirs() }
        if (!src.exists() || !src.isDirectory) return

        src.resolve("commands.json").takeIf { it.exists() }?.copyTo(
            File(dst, "commands-$shardPrefix($cleanFlow).json"),
            overwrite = true,
        )

        src.listFiles { _, name -> name.startsWith("screenshot-") && name.endsWith(".png") }
            ?.forEach { shot ->
                val match = SCREENSHOT_NAME.matchEntire(shot.name) ?: return@forEach
                val (emoji, ts) = match.destructured
                shot.copyTo(
                    File(dst, "screenshot-$shardPrefix$emoji-$ts-($cleanFlow).png"),
                    overwrite = true,
                )
            }
    }

    private val SCREENSHOT_NAME = Regex("screenshot-(.+)-(\\d+)\\.png")

    fun deleteOldFiles(days: Long = 14) {
        try {
            val currentTime = Instant.now()
            val daysLimit = currentTime.minus(Duration.of(days, ChronoUnit.DAYS))

            val logParentDirectory = getDebugOutputPath().parent
            logger.info("Performing purge of logs older than $days days from ${logParentDirectory.absolutePathString()}")

            Files.walk(logParentDirectory).filter {
                val fileTime = Files.getAttribute(it, "basic:lastModifiedTime") as FileTime
                val isOlderThanLimit = fileTime.toInstant().isBefore(daysLimit)
                val shouldBeDeleted = Files.isDirectory(it) && isOlderThanLimit
                if (shouldBeDeleted) {
                    logger.info("Deleting old directory: ${it.absolutePathString()}")
                }
                shouldBeDeleted
            }.sorted(Comparator.reverseOrder()).forEach { dir ->
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { file -> Files.delete(file) }
            }
        } catch (e: Exception) {
            logger.warn("Failed to delete older files", e)
        }
    }

    private fun logSystemInfo() {
        logger.info("Debug output path: {}", getDebugOutputPath().absolutePathString())

        // Disable specific gRPC and Netty loggers
        Configurator.setLevel("io.grpc.netty.NettyClientHandler", Level.OFF)
        Configurator.setLevel("io.grpc.netty", Level.OFF)
        Configurator.setLevel("io.netty", Level.OFF)

        val logger = LogManager.getLogger("MAESTRO")
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
        this.sessionFolderName = folderNameFormatter.format(LocalDateTime.now())
        val path = getDebugOutputPath()
        LogConfig.configure(logFileName = path.absolutePathString() + "/maestro.log", printToConsole = printToConsole)
        logSystemInfo()
        DebugLogStore.logSystemInfo()
    }

    fun updateTestOutputDir(testOutputDir: Path?) {
        this.testOutputDir = testOutputDir
        // Reset debugOutputPath so getDebugOutputPath() will properly handle directory creation
        debugOutputPath = null
    }

    fun getDebugOutputPath(): Path {
        if (debugOutputPath != null) return debugOutputPath as Path

        val debugOutput = when {
            flattenDebugOutput -> Paths.get(debugOutputPathAsString ?: System.getProperty("user.home"))
            else -> buildDefaultDebugOutputPath(debugOutputPathAsString)
        }

        if (!debugOutput.exists()) {
            Files.createDirectories(debugOutput)
        }
        debugOutputPath = debugOutput
        return debugOutput
    }

    private fun buildDefaultDebugOutputPath(customRootPath: String? = null): Path {
        val foldername = sessionFolderName
            ?: folderNameFormatter.format(LocalDateTime.now())
        return when {
            testOutputDir != null -> testOutputDir!!.resolve(foldername)
            customRootPath != null -> Paths.get(customRootPath, ".maestro", "tests", foldername)
            else -> EnvUtils.xdgStateHome().resolve("tests").resolve(foldername)
        }
    }
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
