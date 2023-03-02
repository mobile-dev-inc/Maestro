package maestro.cli.report

object ReporterFactory {

    fun buildReporter(format: ReportFormat, testSuiteName: String?): TestSuiteReporter {
        return when (format) {
            ReportFormat.JUNIT -> JUnitTestSuiteReporter.xml(testSuiteName)
            ReportFormat.NOOP -> TestSuiteReporter.NOOP
        }
    }

}