package maestro.cli.report

import okio.sink
import java.io.File

object ReporterFactory {

    private const val DEFAULT_JUNIT_OUTPUT_FILE = "report.xml"
    private const val DEFAULT_HTML_OUTPUT_FILE = "report.html"
    private const val DEFAULT_ALLURE_OUTPUT_DIR = "allure-results"

    fun buildReporter(
        format: ReportFormat,
        output: File?,
        testSuiteName: String?,
    ): TestSuiteReporter {
        return when (format) {
            ReportFormat.JUNIT -> JUnitTestSuiteReporter.xml(
                sink = (output ?: File(DEFAULT_JUNIT_OUTPUT_FILE)).sink(),
                testSuiteName = testSuiteName,
            )

            ReportFormat.NOOP -> TestSuiteReporter.NOOP
            ReportFormat.ALLURE -> AllureTestSuiteReporter.from(
                output = output ?: File(DEFAULT_ALLURE_OUTPUT_DIR),
                testSuiteName = testSuiteName,
            )
            ReportFormat.HTML -> HtmlTestSuiteReporter(
                out = (output ?: File(DEFAULT_HTML_OUTPUT_FILE)).sink(),
                detailed = false,
            )

            ReportFormat.HTML_DETAILED -> HtmlTestSuiteReporter(
                out = (output ?: File(DEFAULT_HTML_OUTPUT_FILE)).sink(),
                detailed = true,
            )
        }
    }

}