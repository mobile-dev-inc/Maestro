package maestro.cli.model

import kotlin.time.Duration

// TODO: Some properties should be implemented as getters, but it's not possible.
//  See https://github.com/Kotlin/kotlinx.serialization/issues/805
data class TestExecutionSummary(
    val passed: Boolean,
    val suites: List<SuiteResult>,
    val passedCount: Int? = null,
    val totalTests: Int? = null,
    /** Present for Maestro Cloud runs; same URL as printed after "Visit Maestro Cloud for more details about this upload:" */
    val cloudUploadUrl: String? = null,
    /** Present when the cloud API returns an app binary id (logged as "App binary id: …"). */
    val appBinaryId: String? = null,
) {

    data class SuiteResult(
        val passed: Boolean,
        val flows: List<FlowResult>,
        val duration: Duration? = null,
        val startTime: Long? = null,
        val deviceName: String? = null,
    ) {
        fun failures(): List<FlowResult> = flows.filter { it.status == FlowStatus.ERROR }
    }

    data class ReportingMetadata(
        val id: String? = null,
        val classname: String? = null,
    )

    data class FlowResult(
        val name: String,
        val fileName: String?,
        val status: FlowStatus,
        val failure: Failure? = null,
        val duration: Duration? = null,
        val startTime: Long? = null,
        val reportingMetadata: ReportingMetadata? = null,
        val properties: Map<String, String>? = null,
        val tags: List<String>? = null,
        val steps: List<StepResult> = emptyList(),
        val filePath: String? = null,
    )

    data class StepResult(
        val description: String,
        val status: String,
        val duration: String,
    )

    data class Failure(
        val message: String,
    )
}
