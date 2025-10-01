package maestro.cli.analytics

import maestro.cli.util.EnvUtils
import maestro.cli.util.IOSEnvUtils
import maestro.device.util.AndroidEnvUtils
import kotlin.time.Duration

/**
 * Event-specific analytics functions for Maestro CLI.
 * Contains functions that track specific business events.
 * Uses Analytics internally for core functionality.
 */
object AnalyticsEvents {
    /**
     * CLI usage tracking
     */
    fun trackCliCommandRun(command: String) {
        val event = CliCommandRunEvent(
            command = command
        )
        Analytics.trackEvent(event)
    }

    /**
     * 'maestro test' track
     */
    fun trackTestRunStart(flowCount: Int, deviceCount: Int, platform: String) {
        val event = TestRunStartedEvent(
            flowCount = flowCount,
            deviceCount = deviceCount,
            platform = platform
        )
        Analytics.trackEvent(event)
    }

    fun trackTestRunFinished(flowCount: Int, deviceCount: Int, platform: String, allSuccess: Boolean, duration: Long?) {
        val event = TestRunFinishedEvent(
            flowCount = flowCount,
            deviceCount = deviceCount,
            platform = platform,
            allSuccess = allSuccess,
            durationMs = duration ?: 0
        )
        Analytics.trackEvent(event)
    }

    /**
     * 'maestro record' track
     */
    fun trackRecordStart(platform: String) {
        val event = RecordStartedEvent(
            platform = platform,
        )
        Analytics.trackEvent(event)
    }

    fun trackRecordFinished(platform: String, durationMs: Long) {
        val event = RecordFinishedEvent(
            platform = platform,
            durationMs = durationMs,
        )
        Analytics.trackEvent(event)
    }

    /**
     * Cloud upload tracking
     */
    fun trackCloudUploadTriggered(projectId: String, platform: String, isBinaryUpload: Boolean, usesEnvironment: Boolean, deviceModel: String?, deviceOs: String?) {
        val event = CloudUploadTriggeredEvent(
            projectId = projectId,
            platform = platform,
            isBinaryUpload = isBinaryUpload,
            usesEnvironment = usesEnvironment,
            deviceModel = deviceModel,
            deviceOs = deviceOs
        )
        Analytics.trackEvent(event)
    }

    fun trackCloudUploadStart(projectId: String, platform: String, isBinaryUpload: Boolean, usesEnvironment: Boolean, deviceModel: String?, deviceOs: String?) {
        val event = CloudUploadStartedEvent(
            projectId = projectId,
            platform = platform,
            isBinaryUpload = isBinaryUpload,
            usesEnvironment = usesEnvironment,
            deviceModel = deviceModel,
            deviceOs = deviceOs
        )
        Analytics.trackEvent(event)
    }

    fun trackCloudUploadFinished(projectId: String?, success: Boolean, durationMs: Long? = null) {
        val event = CloudUploadFinishedEvent(
            projectId = projectId,
            success = success,
            durationMs = durationMs
        )
        Analytics.trackEvent(event)
    }

    /**
     * Print hierarchy tracking
     */
    fun trackPrintHierarchyStart(platform: String) {
        val event = PrintHierarchyStartedEvent(
            platform = platform
        )
        Analytics.trackEvent(event)
    }

    fun trackPrintHierarchyFinished(platform: String, success: Boolean, durationMs: Long, errorMessage: String? = null) {
        val event = PrintHierarchyFinishedEvent(
            platform = platform,
            success = success,
            durationMs = durationMs,
            errorMessage = errorMessage
        )
        Analytics.trackEvent(event)
    }
}
