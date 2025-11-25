package maestro.cli.report

import maestro.cli.model.TestExecutionSummary
import okio.BufferedSink

object ReporterFactory {

    fun buildReporter(format: ReportFormat, testSuiteName: String?, pretty: Boolean = false): TestSuiteReporter {
        return when (format) {
            ReportFormat.JUNIT -> JUnitTestSuiteReporter.xml(testSuiteName)
            ReportFormat.NOOP -> TestSuiteReporter.NOOP
            ReportFormat.HTML -> HtmlTestSuiteReporter(pretty = pretty)
        }
    }

}