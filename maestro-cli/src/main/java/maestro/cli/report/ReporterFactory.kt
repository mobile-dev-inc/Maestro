package maestro.cli.report

object ReporterFactory {
    fun buildReporter(format: ReportFormat, testSuiteName: String?): TestSuiteReporter {
        return format.createReporter(testSuiteName)
    }
}
