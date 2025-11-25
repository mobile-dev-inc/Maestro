package maestro.cli.report

import maestro.cli.model.TestExecutionSummary
import okio.BufferedSink

object ReporterFactory {

    fun buildReporter(format: ReportFormat, testSuiteName: String?): TestSuiteReporter {
        return when (format) {
            ReportFormat.JUNIT -> JUnitTestSuiteReporter.xml(testSuiteName)
            ReportFormat.NOOP -> TestSuiteReporter.NOOP
            ReportFormat.HTML -> HtmlTestSuiteReporter(detailed = false)
            ReportFormat.HTML_DETAILED -> HtmlTestSuiteReporter(detailed = true)
        }
    }

}