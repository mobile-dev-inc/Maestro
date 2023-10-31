package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
import maestro.cli.runner.CommandStatus
import maestro.debuglog.DebugLogStore
import maestro.debuglog.LogConfig
import maestro.orchestra.MaestroCommand
import maestro.utils.MaestroDirectory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

object TestDebugReporter {

    private val logger = LoggerFactory.getLogger(TestDebugReporter::class.java)
    private val mapper = ObjectMapper()

    private var debugOutputPath: Path? = null
    private var debugOutputPathAsString: String? = null

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
        val osName = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val architecture = System.getProperty("os.arch")


        val logger = LoggerFactory.getLogger("MAESTRO")
        logger.info("---- System Info ----")
        logger.info("Maestro Version: ${appVersion.getOrNull() ?: "Undefined"}")
        logger.info("OS Name: $osName")
        logger.info("OS Version: ${osVersion}")
        logger.info("Architecture: $architecture")
        logger.info("---------------------")
    }

    fun install(debugOutputPathAsString: String?) {
        this.debugOutputPathAsString = debugOutputPathAsString
        val path = getDebugOutputPath()
        LogConfig.configure(path.absolutePathString() + "/maestro.log")
        logSystemInfo()
        DebugLogStore.logSystemInfo()
    }

    fun getDebugOutputPath(): Path {
        if (debugOutputPath != null) return debugOutputPath as Path

        val dateFormat = "yyyy-MM-dd_HHmmss"
        val dateFormatter = DateTimeFormatter.ofPattern(dateFormat)
        val folderName = dateFormatter.format(LocalDateTime.now())
        val debugOutput =
            (debugOutputPathAsString?.let(::Path) ?: MaestroDirectory.getMaestroDirectory()) / "tests" / folderName
        if (!debugOutput.exists()) {
            Files.createDirectories(debugOutput)
        }
        debugOutputPath = debugOutput
        return debugOutput
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

