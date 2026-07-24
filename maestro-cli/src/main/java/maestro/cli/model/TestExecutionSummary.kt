package maestro.cli.model

import kotlin.time.Duration

// TODO: Some properties should be implemented as getters, but it's not possible.
//  See https://github.com/Kotlin/kotlinx.serialization/issues/805
data class TestExecutionSummary(
    val passed: Boolean,
    val suites: List<SuiteResult>,
    val passedCount: Int? = null,
    val totalTests: Int? = null,
) {

    data class SuiteResult(
        val passed: Boolean,
        val flows: List<FlowResult>,
        val duration: Duration? = null,
        val startTime: Long? = null,
        val deviceName: String? = null,
        /**
         * Cloud metadata below is present only for Maestro Cloud runs, which always produce a
         * single suite per upload — hence it lives here rather than on [TestExecutionSummary].
         */
        /** The upload id shared by every flow in this suite. */
        val cloudUploadId: String? = null,
        /** Same URL as printed after "Visit Maestro Cloud for more details about this upload:" */
        val cloudUploadUrl: String? = null,
        /** Present when the cloud API returns an app binary id (logged as "App binary id: …"). */
        val appBinaryId: String? = null,
    ) {
        fun failures(): List<FlowResult> = flows.filter { it.status == FlowStatus.ERROR }
    }

    data class FlowResult(
        val name: String,
        val fileName: String?,
        val status: FlowStatus,
        val failure: Failure? = null,
        val duration: Duration? = null,
        val startTime: Long? = null,
        val properties: Map<String, String>? = null,
        val tags: List<String>? = null,
        val steps: List<StepResult> = emptyList(),
        val filePath: String? = null,
        /** Present for Maestro Cloud runs; the per-flow run id. */
        val cloudRunId: String? = null,
        /** Present for Maestro Cloud runs; deep link to this flow's run on Maestro Cloud. */
        val cloudRunUrl: String? = null,
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
