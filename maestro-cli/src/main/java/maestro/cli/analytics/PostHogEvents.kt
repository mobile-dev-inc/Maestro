package maestro.cli.analytics

import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils
import maestro.device.util.AndroidEnvUtils

/**
 * Strongly-typed PostHog events for Maestro CLI using discriminated unions
 * This ensures compile-time type safety for all analytics events
 */

/**
 * Super properties that are automatically included with every event
 */
data class SuperProperties(
    val app_version: String,
    val platform: String,
    val env: String,
    val app: String = "cli",
    val cli_version: String,
    val java_version: String,
    val os_arch: String,
    val os_version: String,
    val xcode_version: String? = null,
    val flutter_version: String? = null,
    val flutter_channel: String? = null,
    val android_versions: List<String>? = null,
    val ios_versions: List<String>? = null,
) {
    /**
     * Convert to Map for analytics tracking
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
          "app_version" to app_version,
          "platform" to platform,
          "env" to env,
          "app" to app,
          "cli_version" to cli_version,
          "java_version" to java_version,
          "os_arch" to os_arch,
          "os_version" to os_version,
          "xcode_version" to xcode_version,
          "flutter_version" to flutter_version,
          "flutter_channel" to flutter_channel,
          "android_versions" to android_versions,
          "ios_versions" to ios_versions
        ) as Map<String, Any>
    }
    
    /**
     * Create SuperProperties with current system information
     */
    companion object {
        fun create(): SuperProperties {
            return SuperProperties(
                app_version = EnvUtils.getVersion().toString(),
                platform = EnvUtils.OS_NAME,
                env = if (System.getenv("MAESTRO_API_URL") != null) "dev" else "prod",
                app = "cli",
                cli_version = EnvUtils.CLI_VERSION.toString(),
                java_version = EnvUtils.getJavaVersion().toString(),
                os_arch = EnvUtils.OS_ARCH,
                os_version = EnvUtils.OS_VERSION,
                xcode_version = IOSEnvUtils.xcodeVersion,
                flutter_version = EnvUtils.getFlutterVersionAndChannel().first,
                flutter_channel = EnvUtils.getFlutterVersionAndChannel().second,
                android_versions = AndroidEnvUtils.androidEmulatorSdkVersions,
                ios_versions = IOSEnvUtils.simulatorRuntimes
            )
        }
    }
}

/**
 * User properties for user identification
 */
data class UserProperties(
    val user_id: String?,
    val email: String?,
    val name: String?,
    val organizationId: String? = null,
    val org_id: String?,
    val org_name: String?,
    val plan: String?,
    val orgPlan: String?,
    val orgTrialExpiresOn: String?,
) {
    /**
     * Convert to Map for analytics tracking
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "user_id" to user_id,
            "email" to email,
            "name" to name,
            "organizationId" to organizationId,
            "org_id" to org_id,
            "org_name" to org_name,
            "plan" to plan,
            "orgPlan" to orgPlan,
            "orgTrialExpiresOn" to orgTrialExpiresOn
        ) as Map<String, Any>
    }
    
    /**
     * Create UserProperties from AnalyticsState
     */
    companion object {
        fun fromAnalyticsState(state: AnalyticsState): UserProperties {
            return UserProperties(
                user_id = state.user_id,
                email = state.email,
                name = state.name,
                organizationId = state.orgId,
                org_id = state.orgId,
                org_name = state.orgName,
                plan = state.orgPlan,
                orgPlan = state.orgPlan,
                orgTrialExpiresOn = state.orgTrialExpiresOn,
            )
        }
    }
}

/**
 * Base interface for all PostHog events
 */
sealed interface PostHogEvent {
    val name: String
}

/**
 * CLI Usage Events
 */
sealed interface CliUsageEvent : PostHogEvent

data class CliCommandRunEvent(
  override val name: String = "maestro_cli_command_run",
  val command: String
) : CliUsageEvent

/**
 * Test execution events (running individual flows/tests)
 */
sealed interface TestRunEvent : PostHogEvent

data class TestRunStartedEvent(
    override val name: String = "maestro_cli_test_run_started",
    val flowCount: Int,
    val platform: String,
    val deviceCount: Int
) : TestRunEvent

data class TestRunFinishedEvent(
    override val name: String = "maestro_cli_test_run_finished",
    val flowCount: Int,
    val platform: String,
    val deviceCount: Int,
    val allSuccess: Boolean,
    val durationMs: Long
) : TestRunEvent

/**
 * Record Screen Event
 */
sealed interface RecordEvent : PostHogEvent

data class RecordStartedEvent(
    override val name: String = "maestro_cli_record_start",
    val platform: String,
) : RecordEvent

data class RecordFinishedEvent(
  override val name: String = "maestro_cli_record_finished",
  val platform: String,
  val durationMs: Long
) : RecordEvent

/**
 * Cloud Upload Events
 */
sealed interface CloudUploadEvent : PostHogEvent

data class CloudUploadTriggeredEvent(
    override val name: String = "maestro_cli_cloud_upload_triggered",
    val projectId: String,
    val platform: String,
    val isBinaryUpload: Boolean = false,
    val usesEnvironment: Boolean = false,
    val deviceModel: String? = null,
    val deviceOs: String? = null
) : CloudUploadEvent

data class CloudUploadStartedEvent(
    override val name: String = "maestro_cli_cloud_upload_started",
    val projectId: String,
    val platform: String,
    val isBinaryUpload: Boolean = false,
    val usesEnvironment: Boolean = false,
    val deviceModel: String? = null,
    val deviceOs: String? = null
) : CloudUploadEvent

data class CloudUploadFinishedEvent(
    override val name: String = "maestro_cli_cloud_upload_finished",
    val projectId: String? = null,
    val success: Boolean? = null,
    val durationMs: Long? = null,
) : CloudUploadEvent

/**
 * Print Hierarchy Events
 */
sealed interface PrintHierarchyEvent : PostHogEvent

data class PrintHierarchyStartedEvent(
    override val name: String = "maestro_cli_print_hierarchy_started",
    val platform: String
) : PrintHierarchyEvent

data class PrintHierarchyFinishedEvent(
    override val name: String = "maestro_cli_print_hierarchy_finished",
    val platform: String,
    val success: Boolean,
    val durationMs: Long,
    val errorMessage: String? = null
) : PrintHierarchyEvent

/**
 * Trial Events
 */
sealed interface TrialEvent : PostHogEvent

data class TrialStartPromptedEvent(
    override val name: String = "maestro_cli_trial_start_prompted",
) : TrialEvent

data class TrialStartedEvent(
    override val name: String = "maestro_cli_trial_started",
    val companyName: String,
) : TrialEvent

data class TrialStartFailedEvent(
    override val name: String = "maestro_cli_trial_start_failed",
    val companyName: String,
    val failureReason: String,
) : TrialEvent
