package maestro.cli.report

import maestro.cli.model.TestExecutionSummary
import okio.Sink

interface TestSuiteReporter {

    /**
     * Writes the report for [summary] to [out] in the format specified by the implementation.
     */
    fun report(
        summary: TestExecutionSummary,
        out: Sink,
    )

    companion object {
        val NOOP: TestSuiteReporter = object : TestSuiteReporter {
            override fun report(summary: TestExecutionSummary, out: Sink) {
                // no-op
            }
        }
    }
}
