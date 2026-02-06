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
