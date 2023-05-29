package maestro.cli.model

import kotlin.time.Duration

data class TestExecutionSummary(
    val passed: Boolean,
    val suites: List<SuiteResult>,
    val deviceName: String? = null,
) {

    data class SuiteResult(
        val passed: Boolean,
        val flows: List<FlowResult>,
        val duration: Duration? = null,
    )

    data class FlowResult(
        val name: String,
        val fileName: String?,
        val status: FlowStatus,
        val failure: Failure? = null,
        val duration: Duration? = null,
    )

    data class Failure(
        val message: String,
    )

}