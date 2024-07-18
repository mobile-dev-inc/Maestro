package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
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
import java.util.IdentityHashMap
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

object TestDebugReporter {

    private val logger = LoggerFactory.getLogger(TestDebugReporter::class.java)
    private val mapper = ObjectMapper()

    private var debugOutputPath: Path? = null
    private var debugOutputPathAsString: String? = null
    private var flattenDebugOutput: Boolean = false

    init {

        // json
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
    }

    fun saveFlow(flowName: String, data: FlowDebugMetadata, path: Path) {

        // commands
        try {
            val commandMetadata = data.commands
            if (commandMetadata.isNotEmpty()) {
                val commandsFilename = "commands-(${flowName.replace("/", "_")}).json"
                val file = File(path.absolutePathString(), commandsFilename)
                commandMetadata.map { CommandDebugWrapper(it.key, it.value) }.let {
                    mapper.writeValue(file, it)
                }
            }
        } catch (e: JsonMappingException) {
            logger.error("Unable to parse commands", e)
        }

        // screenshots
        data.screenshots.forEach {
            val status = when (it.status) {
                CommandStatus.COMPLETED -> "✅"
                CommandStatus.FAILED -> "❌"
                else -> "﹖"
            }
            val name = "screenshot-$status-${it.timestamp}-(${flowName}).png"
            val file = File(path.absolutePathString(), name)

            it.screenshot.copyTo(file)
        }
    }

    fun deleteOldFiles(days: Long = 14) {
        try {
            val currentTime = Instant.now()
            val daysLimit = currentTime.minus(Duration.of(days, ChronoUnit.DAYS))

            Files.walk(getDebugOutputPath())
                .filter {
                    val fileTime = Files.getAttribute(it, "basic:lastModifiedTime") as FileTime
                    val isOlderThanLimit = fileTime.toInstant().isBefore(daysLimit)
                    Files.isDirectory(it) && isOlderThanLimit
                }
                .sorted(Comparator.reverseOrder())
                .forEach {
                    Files.walk(it)
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.delete(it) }
                }
        } catch (e: Exception) {
            logger.warn("Failed to delete older files", e)
        }
    }

    private fun logSystemInfo() {
        val appVersion = runCatching {
            val props = Driver::class.java.classLoader.getResourceAsStream("version.properties").use {
                Properties().apply { load(it) }
            }
            props["version"].toString()
        }

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

    fun install(debugOutputPathAsString: String?, flattenDebugOutput: Boolean = false) {
        this.debugOutputPathAsString = debugOutputPathAsString
        this.flattenDebugOutput = flattenDebugOutput
        val path = getDebugOutputPath()
        LogConfig.configure(path.absolutePathString() + "/maestro.log")
        logSystemInfo()
        DebugLogStore.logSystemInfo()
    }

    fun getDebugOutputPath(): Path {
        if (debugOutputPath != null) return debugOutputPath as Path

        val debugRootPath = if(debugOutputPathAsString != null) debugOutputPathAsString!! else System.getProperty("user.home")        
        val debugOutput = if(flattenDebugOutput) Paths.get(debugRootPath) else buildDefaultDebugOutputPath(debugRootPath)
        
        if (!debugOutput.exists()) {
            Files.createDirectories(debugOutput)
        }
        debugOutputPath = debugOutput
        return debugOutput
    }

    fun buildDefaultDebugOutputPath(debugRootPath: String): Path {
        val preamble = arrayOf(".maestro", "tests")
        val foldername = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").format(LocalDateTime.now())
        return Paths.get(debugRootPath, *preamble, foldername)
    }

}

private data class CommandDebugWrapper(
    val command: MaestroCommand,
    val metadata: CommandDebugMetadata
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

data class ScreenshotDebugMetadata(
    val screenshot: File,
    val timestamp: Long,
    val status: CommandStatus
)

data class FlowDebugMetadata(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    val screenshots: MutableList<ScreenshotDebugMetadata> = mutableListOf(),
    var exception: MaestroException? = null
)
