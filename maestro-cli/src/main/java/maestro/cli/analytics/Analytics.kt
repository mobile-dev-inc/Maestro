package maestro.cli.analytics

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import maestro.cli.api.ApiClient
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The new analytics system for Maestro CLI.
 *  - Sends data to /maestro/analytics endpoint.
 *  - Uses configuration from $XDG_CONFIG_HOME/maestro/analytics.json.
 */
object Analytics {
    private val logger = LoggerFactory.getLogger(Analytics::class.java)
    private val analyticsStatePath: Path = EnvUtils.xdgStateHome().resolve("analytics.json")
    private val legacyUuidPath: Path = EnvUtils.legacyMaestroHome().resolve("uuid")

    private val JSON = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    private val hasRunBefore: Boolean
        get() = legacyUuidPath.exists() || analyticsStatePath.exists()

    private val analyticsState: AnalyticsState
        get() = JSON.readValue<AnalyticsState>(analyticsStatePath.readText())

    private val uploadConditionsMet: Boolean
        get() {
            val lastUploadedTime = analyticsState.lastUploadedTime ?: return true
            val passed = lastUploadedTime.plus(Duration.ofDays(7)).isBefore(Instant.now())
            logger.trace(
                if (passed) "Last upload was more than a week ago, uploading"
                else "Last upload was less than a week ago, not uploading"
            )
            return passed
        }


    val uuid: String
        get() = analyticsState.uuid

    fun maybeMigrate() {
        // Previous versions of Maestro (<1.36.0) used ~/.maestro/uuid to store uuid.
        // If ~/.maestro/uuid already exists, assume permission was granted (for backward compatibility).
        if (legacyUuidPath.exists()) {
            val uuid = legacyUuidPath.readText()
            saveAnalyticsState(granted = true, uuid = uuid)
            legacyUuidPath.deleteExisting()
        }
    }

    fun maybeAskToEnableAnalytics() {
        if (hasRunBefore) return

        while (!Thread.interrupted()) {
            println("Maestro CLI would like to collect anonymous usage data to improve the product.")
            print("Enable analytics? [Y/n] ")

            val str = readlnOrNull()?.lowercase()
            val granted = str?.isBlank() == true || str == "y" || str == "yes"
            println(
                if (granted) "Usage data collection enabled. Thank you!"
                else "Usage data collection disabled."
            )
            saveAnalyticsState(granted)
            return
        }

        error("Interrupted")
    }

    /**
     * Uploads analytics if there was a version update.
     */
    fun maybeUploadAnalyticsAsync() {
        if (!hasRunBefore) {
            logger.trace("First run, not uploading")
            return
        }

        if (!analyticsState.enabled) {
            logger.trace("Analytics disabled, not uploading")
            return
        }

        if (!uploadConditionsMet) {
            logger.trace("Upload conditions not met, not uploading")
            return
        }

        val report = AnalyticsReport(
            uuid = analyticsState.uuid,
            freshInstall = !hasRunBefore,
            cliVersion = EnvUtils.CLI_VERSION?.toString() ?: "Unknown",
            os = EnvUtils.OS_NAME,
            osArch = EnvUtils.OS_ARCH,
            osVersion = EnvUtils.OS_VERSION,
            javaVersion = EnvUtils.getJavaVersion().toString(),
            xcodeVersion = EnvUtils.getXcodeVersion(),
            flutterVersion = EnvUtils.getFlutterVersionAndChannel().first,
            flutterChannel = EnvUtils.getFlutterVersionAndChannel().second,
        )

        logger.trace("Will upload analytics report")
        logger.trace(report.toString())

        try {
            ApiClient(EnvUtils.BASE_API_URL).sendAnalyticsReport(report)
            updateAnalyticsState()
        } catch (e: ConnectException) {
            // This is fine. We don't care that much about analytics to bug user about it.
            return
        }
    }

    private fun saveAnalyticsState(
        granted: Boolean,
        uuid: String? = null,
    ): AnalyticsState {
        val state = AnalyticsState(
            uuid = uuid ?: generateUUID(),
            enabled = granted,
            lastUploadedForCLI = null,
            lastUploadedTime = null,
        )
        val stateJson = JSON.writeValueAsString(state)
        analyticsStatePath.parent.createDirectories()
        analyticsStatePath.writeText(stateJson + "\n")
        logger.trace("Saved analytics to {}, value: {}", analyticsStatePath, stateJson)
        return state
    }

    private fun updateAnalyticsState() {
        val stateJson = JSON.writeValueAsString(
            analyticsState.copy(
                lastUploadedForCLI = EnvUtils.CLI_VERSION?.toString(),
                lastUploadedTime = Instant.now(),
            )
        )

        analyticsStatePath.writeText(stateJson + "\n")
        logger.trace("Updated analytics at {}, value: {}", analyticsStatePath, stateJson)
    }

    private fun generateUUID(): String {
        return CiUtils.getCiProvider() ?: UUID.randomUUID().toString()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsState(
    val uuid: String,
    val enabled: Boolean,
    val lastUploadedForCLI: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") val lastUploadedTime: Instant?,
)

// AnalyticsReport must match equivalent monorepo model in:
// mobile.dev/api/models/src/main/java/models/maestro/AnalyticsReport.kt
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsReport(
    @JsonProperty("deviceUuid") val uuid: String,
    @JsonProperty("freshInstall") val freshInstall: Boolean,
    @JsonProperty("version") val cliVersion: String,
    @JsonProperty("os") val os: String,
    @JsonProperty("osArch") val osArch: String,
    @JsonProperty("osVersion") val osVersion: String,
    @JsonProperty("javaVersion") val javaVersion: String?,
    @JsonProperty("xcodeVersion") val xcodeVersion: String?,
    @JsonProperty("flutterVersion") val flutterVersion: String?,
    @JsonProperty("flutterChannel") val flutterChannel: String?,
    // TODO(bartek): List of Android versions of created Android emulators (alternative: list of downlaoded system-images)
    // TODO(bartek): List of installed iOS Simulator runtimes
)
