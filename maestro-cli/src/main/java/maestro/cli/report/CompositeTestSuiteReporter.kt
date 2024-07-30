package maestro.cli.report

import maestro.cli.model.TestExecutionSummary
import java.io.File

class CompositeTestSuiteReporter(val reporters: Set<TestSuiteReporter>, override val fileExtension: String?) : TestSuiteReporter {
    override fun report(
        summary: TestExecutionSummary,
        out: File
    ) {
        val baseReportDirectory = getBaseReportDirectory(out)

        reporters.forEach { reporter ->
            val reportFolder = File(baseReportDirectory, simpleReportFolderName(reporter))
            reportFolder.mkdirs()

            val reportFile = File(reportFolder, "report${reporter.fileExtension}")

            if (reportFile.exists()) {
                reportFile.delete()
            }

            reporter.report(summary, reportFile)
        }
    }

    private fun getBaseReportDirectory(out: File): File {
        return if (out.absoluteFile.isDirectory) {
            out.absoluteFile
        } else {
            out.absoluteFile.parentFile
        }
    }

    private fun simpleReportFolderName(reporter: TestSuiteReporter): String {
        return reporter.javaClass.simpleName.replace(interfaceName, "")
    }

    companion object {
        private val interfaceName = TestSuiteReporter::class.simpleName!!
    }
}
