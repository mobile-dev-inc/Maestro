package maestro.cli.report

enum class ReportFormat(
    val fileExtension: String?
) {
    JUNIT(".xml") {
        override fun createReporter(testSuiteName: String?): TestSuiteReporter {
            return JUnitTestSuiteReporter.xml(testSuiteName, fileExtension)
        }
    },
    HTML(".html") {
        override fun createReporter(testSuiteName: String?): TestSuiteReporter {
            return HtmlTestSuiteReporter(fileExtension)
        }
    },
    COMPOSITE(".composite") {
        override fun createReporter(testSuiteName: String?): TestSuiteReporter {
            return CompositeTestSuiteReporter(
                setOf(
                    HTML.createReporter(testSuiteName),
                    JUNIT.createReporter(testSuiteName)
                ),
                fileExtension
            )
        }
    },
    NOOP(null) {
        override fun createReporter(testSuiteName: String?): TestSuiteReporter {
            return TestSuiteReporter.NOOP
        }
    };

    abstract fun createReporter(testSuiteName: String?): TestSuiteReporter
}
