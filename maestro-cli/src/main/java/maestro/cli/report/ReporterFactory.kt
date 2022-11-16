package maestro.cli.report

object ReporterFactory {

    fun buildReporter(format: ReportFormat): TestSuiteReporter {
        return when (format) {
            ReportFormat.JUNIT -> JUnitTestSuiteReporter.xml()
            ReportFormat.NOOP -> TestSuiteReporter.NOOP
        }
    }

}