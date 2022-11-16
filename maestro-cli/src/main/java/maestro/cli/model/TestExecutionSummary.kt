package maestro.cli.model

data class TestExecutionSummary(
    val passed: Boolean,
    val suites: List<SuiteResult>,
    val deviceName: String? = null,
) {

    data class SuiteResult(
        val passed: Boolean,
        val flows: List<FlowResult>,
    )

    data class FlowResult(
        val name: String,
        val fileName: String,
        val status: FlowStatus,
        val failure: Failure? = null,
    )

    data class Failure(
        val message: String,
    )

}