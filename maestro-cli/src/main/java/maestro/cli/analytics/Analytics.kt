package maestro.cli.analytics

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.posthog.java.PostHog
import maestro.cli.util.CiUtils
import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils
import maestro.device.util.AndroidEnvUtils
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The new analytics system for Maestro CLI.
 *  - Sends data to /maestro/analytics endpoint.
 *  - Uses configuration from $XDG_CONFIG_HOME/maestro/analytics.json.
 */
object Analytics : AutoCloseable {
    private const val POSTHOG_API_KEY: String = "phc_XKhdIS7opUZiS58vpOqbjzgRLFpi0I6HU2g00hR7CVg"
    private const val POSTHOG_HOST: String = "https://us.i.posthog.com"

    private val posthog = PostHog.Builder(POSTHOG_API_KEY).host(POSTHOG_HOST).build();

    private val logger = LoggerFactory.getLogger(Analytics::class.java)
    private val analyticsStatePath: Path = EnvUtils.xdgStateHome().resolve("analytics.json")

    private const val DISABLE_ANALYTICS_ENV_VAR = "MAESTRO_CLI_NO_ANALYTICS"
    private val analyticsDisabledWithEnvVar: Boolean
        get() = System.getenv(DISABLE_ANALYTICS_ENV_VAR) != null

    private val JSON = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    val hasRunBefore: Boolean
        get() = analyticsStatePath.exists()

    private val analyticsState: AnalyticsState
        get() {
            return try {
                if (analyticsStatePath.exists()) {
                    JSON.readValue(analyticsStatePath.readText())
                } else {
                    AnalyticsState(
                      uuid = generateUUID(),
                      enabled = false,
                      lastUploadedForCLI = null,
                      lastUploadedTime = null
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to read analytics state: ${e.message}. Using default.")
                AnalyticsState(
                    uuid = generateUUID(),
                    enabled = false,
                    lastUploadedForCLI = null,
                    lastUploadedTime = null
                )
            }
      }

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


    fun maybeAskToEnableAnalytics() {
        if (hasRunBefore) return

        println("Anonymous analytics enabled. To opt out, set $DISABLE_ANALYTICS_ENV_VAR environment variable to any value before running Maestro.\n")
        saveAnalyticsState(granted = !analyticsDisabledWithEnvVar, uuid = uuid)
    }

    /**
     * Uploads analytics if there was a version update.
     */
    fun maybeUploadAnalyticsAsync(commandName: String) {
        try {
            if (analyticsDisabledWithEnvVar) {
                logger.trace("Analytics disabled with env var, not uploading")
                return
            }

            if (!analyticsState.enabled) {
                logger.trace("Analytics disabled with config file, not uploading")
                return
            }

            if (!uploadConditionsMet) {
                logger.trace("Upload conditions not met, not uploading")
                return
            }
            logger.trace("Will upload analytics report")

            posthog.capture(
                uuid,
                "maestro_cli_run",
                mapOf(
                    "command" to commandName,
                    "freshInstall" to !hasRunBefore,
                    "cliVersion" to (EnvUtils.CLI_VERSION?.toString() ?: "Unknown"),
                    "os" to EnvUtils.OS_NAME,
                    "osArch" to EnvUtils.OS_ARCH,
                    "osVersion" to EnvUtils.OS_VERSION,
                    "javaVersion" to EnvUtils.getJavaVersion().toString(),
                    "xcodeVersion" to IOSEnvUtils.xcodeVersion,
                    "flutterVersion" to EnvUtils.getFlutterVersionAndChannel().first,
                    "flutterChannel" to EnvUtils.getFlutterVersionAndChannel().second,
                    "androidVersions" to AndroidEnvUtils.androidEmulatorSdkVersions,
                    "iosVersions" to IOSEnvUtils.simulatorRuntimes,
                )
            )

            updateAnalyticsState()
        } catch (e: ConnectException) {
            // This is fine. The user probably doesn't have internet connection.
            // We don't care that much about analytics to bug user about it.
            return
        } catch (e: Exception) {
            // This is also fine. We don't want to bug the user.
            // See discussion at https://github.com/mobile-dev-inc/maestro/pull/1858
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

    override fun close() {
        posthog.shutdown()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsState(
    val uuid: String,
    val enabled: Boolean,
    val lastUploadedForCLI: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC") val lastUploadedTime: Instant?,
)